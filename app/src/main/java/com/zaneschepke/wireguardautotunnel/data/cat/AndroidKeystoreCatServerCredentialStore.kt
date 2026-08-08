package com.zaneschepke.wireguardautotunnel.data.cat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.CatServerCredentials
import com.dedtsss.catawg.core.protocol.normalizeCertificateFingerprint
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class CredentialEnvelope(
    val version: Int = 1,
    val deviceToken: String,
    val certificateFingerprint: String,
    val deviceId: String,
)

/** Pure serialization boundary used by the Keystore adapter and JVM tests. */
object CatCredentialEnvelopeCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(credentials: CatServerCredentials): ByteArray =
        json
            .encodeToString(
                CredentialEnvelope(
                    deviceToken = credentials.deviceToken,
                    certificateFingerprint =
                        normalizeCertificateFingerprint(credentials.certificateFingerprint),
                    deviceId = credentials.deviceId,
                )
            )
            .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): CatServerCredentials {
        val envelope = json.decodeFromString<CredentialEnvelope>(bytes.toString(Charsets.UTF_8))
        require(envelope.version == 1) { "Unsupported Cat credential envelope" }
        return CatServerCredentials(
            deviceToken = envelope.deviceToken,
            certificateFingerprint =
                normalizeCertificateFingerprint(envelope.certificateFingerprint),
            deviceId = envelope.deviceId,
        )
    }
}

/**
 * AES-GCM encrypted credential storage using an Android Keystore key. The ciphertext is placed in
 * noBackupFilesDir, so a restored app never silently receives an unusable device token.
 */
class AndroidKeystoreCatServerCredentialStore(context: Context) : CatServerCredentialStore {
    private val keyAlias = "${context.packageName}.cat.server.credentials.v1"
    private val credentialFile = File(context.noBackupFilesDir, "cat-server-credentials.v1.bin")
    private val tempFile = File(context.noBackupFilesDir, "cat-server-credentials.v1.tmp")

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val store = keyStore()
        (store.getKey(keyAlias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    @Synchronized
    override fun read(): CatServerCredentials? {
        if (!credentialFile.isFile) return null
        return runCatching {
                val encoded = credentialFile.readBytes()
                require(encoded.size > 13 && encoded[0].toInt() == FORMAT_VERSION.toInt()) {
                    "Invalid Cat credential envelope"
                }
                val ivLength = encoded[1].toInt()
                require(ivLength in 12..32 && encoded.size > ivLength + 2) {
                    "Invalid Cat credential IV"
                }
                val iv = encoded.copyOfRange(2, ivLength + 2)
                val ciphertext = encoded.copyOfRange(ivLength + 2, encoded.size)
                Cipher.getInstance(TRANSFORMATION)
                    .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
                    .doFinal(ciphertext)
                    .let(CatCredentialEnvelopeCodec::decode)
            }
            .getOrElse {
                // A Keystore key can be invalidated by lock-screen migration or uninstall. Do not
                // retain ciphertext that can no longer be used and never log the failed payload.
                credentialFile.delete()
                runCatching { keyStore().deleteEntry(keyAlias) }
                null
            }
    }

    @Synchronized
    override fun write(credentials: CatServerCredentials) {
        require(credentials.deviceToken.isNotBlank()) { "Cat device token must not be empty" }
        require(credentials.deviceId.isNotBlank()) { "Cat device id must not be empty" }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val ciphertext =
            Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
                .doFinal(CatCredentialEnvelopeCodec.encode(credentials))
        val encoded = byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
        FileOutputStream(tempFile).use { it.write(encoded) }
        check(tempFile.renameTo(credentialFile)) { "Could not commit Cat credential storage" }
    }

    @Synchronized
    override fun clear() {
        credentialFile.delete()
        tempFile.delete()
        runCatching { keyStore().deleteEntry(keyAlias) }
    }

    private companion object {
        const val FORMAT_VERSION: Byte = 1
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

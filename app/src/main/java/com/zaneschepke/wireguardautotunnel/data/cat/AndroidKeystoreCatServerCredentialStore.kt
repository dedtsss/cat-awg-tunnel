package com.zaneschepke.wireguardautotunnel.data.cat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.CatServerCredentials
import com.dedtsss.catawg.core.protocol.normalizeCertificateFingerprint
import java.io.File
import java.io.FileOutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
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
        require(envelope.deviceToken.isNotBlank()) { "Cat credential token is empty" }
        require(envelope.deviceId.isNotBlank()) { "Cat credential device id is empty" }
        require(envelope.certificateFingerprint.isNotBlank()) {
            "Cat credential certificate fingerprint is empty"
        }
        return CatServerCredentials(
            deviceToken = envelope.deviceToken,
            certificateFingerprint =
                normalizeCertificateFingerprint(envelope.certificateFingerprint),
            deviceId = envelope.deviceId,
        )
    }
}

/** A safe, non-secret diagnostic for one credential persistence failure. */
data class CatCredentialPersistenceDiagnostic(
    val code: String,
    val exceptionClass: String,
)

/**
 * A local-only failure marker. The code is safe for UI/diagnostics; the cause is retained only so
 * error mapping and local crash diagnostics can identify the failing crypto/storage boundary.
 */
class CatCredentialPersistenceFailure(val code: String, cause: Throwable) :
    IllegalStateException("Cat credential persistence failed at $code", cause)

/**
 * AES-GCM encrypted credential storage using an Android Keystore key. The ciphertext is placed in
 * noBackupFilesDir, so a restored app never silently receives an unusable device token.
 *
 * Android Keystore generates the encryption IV. Passing a caller-generated IV to an AES-GCM key
 * with randomized encryption required is rejected by the AndroidKeyStore provider.
 */
class AndroidKeystoreCatServerCredentialStore(context: Context) : CatServerCredentialStore {
    private val appContext = context.applicationContext
    private val keyAlias = "${appContext.packageName}.cat.server.credentials.v1"
    private val credentialFile = File(appContext.noBackupFilesDir, "cat-server-credentials.v1.bin")
    private val atomicCredentialFile = AtomicFile(credentialFile)

    @Volatile private var lastFailure: CatCredentialPersistenceDiagnostic? = null

    /** Last non-secret failure classification, intended for diagnostics and tests. */
    fun lastFailure(): CatCredentialPersistenceDiagnostic? = lastFailure

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun loadExistingKey(): SecretKey {
        val store =
            try {
                keyStore()
            } catch (error: Throwable) {
                throw failure(KEYSTORE_KEY_FAILED, error)
            }
        val stored =
            try {
                store.getKey(keyAlias, null)
            } catch (error: Throwable) {
                throw failure(KEYSTORE_KEY_FAILED, error)
            }
        return stored as? SecretKey
            ?: throw failure(
                KEYSTORE_KEY_FAILED,
                IllegalStateException("Cat credential Keystore alias is absent or not AES"),
            )
    }

    private fun loadOrCreateKey(): SecretKey {
        val store =
            try {
                keyStore()
            } catch (error: Throwable) {
                throw failure(KEYSTORE_KEY_FAILED, error)
            }
        try {
            (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
            if (store.containsAlias(keyAlias)) {
                store.deleteEntry(keyAlias)
            }
        } catch (error: Throwable) {
            // A stale alias can survive an interrupted or older credential write. Remove only this
            // app-owned alias and recreate it once; no secret state is logged or retained.
            try {
                if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
            } catch (cleanupError: Throwable) {
                throw failure(KEYSTORE_KEY_FAILED, cleanupError)
            }
        }
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setKeySize(KEY_SIZE_BITS)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            // No user-authentication or StrongBox requirement is intentional: Cat
                            // pairing must work on API 33+ devices without either capability.
                            .build()
                    )
                }
                .generateKey()
        } catch (error: Throwable) {
            throw failure(KEYSTORE_KEY_FAILED, error)
        }
    }

    @Synchronized
    override fun read(): CatServerCredentials? {
        if (!hasCredentialState()) return null
        return try {
            readStored()
                .also { lastFailure = null }
        } catch (error: Throwable) {
            val classified = classifyReadFailure(error)
            recordFailure(classified)
            // A credential file and its Keystore key are one unit. If either is unusable, remove
            // both so the next pairing can recreate a clean unit.
            resetLocalStateBestEffort()
            null
        }
    }

    @Synchronized
    override fun write(credentials: CatServerCredentials) {
        val expected =
            try {
                require(credentials.deviceToken.isNotBlank()) { "Cat device token must not be empty" }
                require(credentials.deviceId.isNotBlank()) { "Cat device id must not be empty" }
                require(credentials.certificateFingerprint.isNotBlank()) {
                    "Cat certificate fingerprint must not be empty"
                }
                credentials.copy(
                    certificateFingerprint =
                        normalizeCertificateFingerprint(credentials.certificateFingerprint)
                )
            } catch (error: Throwable) {
                throw recordAndReturnFailure(ENCRYPT_FAILED, error)
            }

        val encoded =
            try {
                encrypt(expected)
            } catch (error: CatCredentialPersistenceFailure) {
                throw recordAndReturnFailure(error.code, error)
            } catch (error: Throwable) {
                throw recordAndReturnFailure(ENCRYPT_FAILED, error)
            }

        var output: FileOutputStream? = null
        try {
            output = atomicCredentialFile.startWrite()
            output.write(encoded)
            output.flush()
            output.fd.sync()
            atomicCredentialFile.finishWrite(output)
            output = null
        } catch (error: Throwable) {
            output?.let { stream -> runCatching { atomicCredentialFile.failWrite(stream) } }
            throw recordAndReturnFailure(CREDENTIAL_WRITE_FAILED, error)
        }

        try {
            val restored = readStored()
            require(restored == expected) { "Cat credential readback did not match" }
            lastFailure = null
        } catch (error: Throwable) {
            val readback =
                if (error is CatCredentialPersistenceFailure && error.code == DECRYPT_FAILED) {
                    CatCredentialPersistenceFailure(CREDENTIAL_READBACK_FAILED, error)
                } else {
                    error
                }
            resetLocalStateBestEffort()
            throw recordAndReturnFailure(CREDENTIAL_READBACK_FAILED, readback)
        }
    }

    @Synchronized
    override fun clear() {
        resetLocalStateBestEffort()
        lastFailure = null
    }

    private fun encrypt(credentials: CatServerCredentials): ByteArray {
        var recreated = false
        while (true) {
            val key = loadOrCreateKey()
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                // Do not provide GCMParameterSpec for encryption. AndroidKeyStore must generate a
                // fresh randomized IV when randomized encryption is required.
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                require(iv != null && iv.size in MIN_IV_BYTES..MAX_IV_BYTES) {
                    "AndroidKeyStore returned an unsupported AES-GCM IV"
                }
                val ciphertext = cipher.doFinal(CatCredentialEnvelopeCodec.encode(credentials))
                return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
            } catch (error: Throwable) {
                if (!recreated && isRecoverableKeyFailure(error)) {
                    resetLocalStateStrict()
                    recreated = true
                    continue
                }
                val code = if (isKeyFailure(error)) KEYSTORE_KEY_FAILED else ENCRYPT_FAILED
                throw failure(code, error)
            }
        }
    }

    private fun readStored(): CatServerCredentials? {
        if (!hasCredentialState()) return null
        val encoded =
            try {
                atomicCredentialFile.openRead().use { it.readBytes() }
            } catch (error: Throwable) {
                throw failure(CREDENTIAL_READ_FAILED, error)
            }
        val parsed =
            try {
                require(encoded.size >= HEADER_BYTES + TAG_BYTES) {
                    "Invalid Cat credential envelope"
                }
                require(encoded[0] == FORMAT_VERSION) { "Unsupported Cat credential format" }
                val ivLength = encoded[1].toInt() and 0xff
                require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid Cat credential IV" }
                require(encoded.size >= HEADER_BYTES + ivLength + TAG_BYTES) {
                    "Truncated Cat credential ciphertext"
                }
                val iv = encoded.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength)
                val ciphertext = encoded.copyOfRange(HEADER_BYTES + ivLength, encoded.size)
                iv to ciphertext
            } catch (error: CatCredentialPersistenceFailure) {
                throw error
            } catch (error: Throwable) {
                throw failure(DECRYPT_FAILED, error)
            }
        val plaintext =
            try {
                Cipher.getInstance(TRANSFORMATION)
                    .apply {
                        init(
                            Cipher.DECRYPT_MODE,
                            loadExistingKey(),
                            GCMParameterSpec(TAG_BITS, parsed.first),
                        )
                    }
                    .doFinal(parsed.second)
            } catch (error: CatCredentialPersistenceFailure) {
                throw error
            } catch (error: Throwable) {
                throw failure(DECRYPT_FAILED, error)
            }
        return try {
            CatCredentialEnvelopeCodec.decode(plaintext)
        } catch (error: Throwable) {
            throw failure(DECRYPT_FAILED, error)
        }
    }

    private fun hasCredentialState(): Boolean =
        credentialFile.isFile || File("${credentialFile.path}.bak").isFile

    private fun resetLocalStateBestEffort() {
        runCatching { atomicCredentialFile.delete() }
        runCatching {
            val store = keyStore()
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        }
    }

    private fun resetLocalStateStrict() {
        atomicCredentialFile.delete()
        val store = keyStore()
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }

    private fun classifyReadFailure(error: Throwable): CatCredentialPersistenceFailure =
        when (error) {
            is CatCredentialPersistenceFailure -> error
            else -> failure(DECRYPT_FAILED, error)
        }

    private fun recordAndReturnFailure(
        code: String,
        error: Throwable,
    ): CatCredentialPersistenceFailure {
        val classified =
            if (error is CatCredentialPersistenceFailure && error.code == code) {
                error
            } else {
                CatCredentialPersistenceFailure(code, error)
            }
        recordFailure(classified)
        return classified
    }

    private fun recordFailure(error: CatCredentialPersistenceFailure) {
        lastFailure =
            CatCredentialPersistenceDiagnostic(
                code = error.code,
                exceptionClass = rootCause(error).javaClass.name,
            )
    }

    private fun failure(code: String, cause: Throwable): CatCredentialPersistenceFailure =
        CatCredentialPersistenceFailure(code, cause)

    private fun isRecoverableKeyFailure(error: Throwable): Boolean =
        isKeyFailure(error) || errorChain(error).any { it is InvalidAlgorithmParameterException }

    private fun isKeyFailure(error: Throwable): Boolean =
        errorChain(error).any {
            it is InvalidKeyException ||
                it is KeyStoreException ||
                it is UnrecoverableKeyException ||
                it is ProviderException
        }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun errorChain(error: Throwable): List<Throwable> = buildList {
        var current: Throwable? = error
        while (current != null && none { it === current }) {
            add(current)
            current = current.cause
        }
    }

    private companion object {
        const val FORMAT_VERSION: Byte = 1
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val HEADER_BYTES = 2
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEYSTORE_KEY_FAILED = "KEYSTORE_KEY_FAILED"
        const val ENCRYPT_FAILED = "ENCRYPT_FAILED"
        const val CREDENTIAL_WRITE_FAILED = "CREDENTIAL_WRITE_FAILED"
        const val CREDENTIAL_READBACK_FAILED = "CREDENTIAL_READBACK_FAILED"
        const val CREDENTIAL_READ_FAILED = "CREDENTIAL_READ_FAILED"
        const val DECRYPT_FAILED = "DECRYPT_FAILED"
    }
}

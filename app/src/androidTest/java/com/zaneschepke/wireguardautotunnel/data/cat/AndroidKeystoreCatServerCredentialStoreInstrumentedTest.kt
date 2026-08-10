package com.zaneschepke.wireguardautotunnel.data.cat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dedtsss.catawg.core.protocol.CatServerCredentials
import java.io.File
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Narrow AndroidKeyStore + AtomicFile runtime coverage for the Cat credential boundary. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCatServerCredentialStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val credentialFile = File(context.noBackupFilesDir, "cat-server-credentials.v1.bin")

    @Test
    fun credentialRoundTripOverwriteAndCorruptStateRecovery() {
        val store = AndroidKeystoreCatServerCredentialStore(context)
        store.clear()
        try {
            assertNull(store.read())

            val first = credential("first-device", "first-token")
            store.write(first)
            assertEquals(first, store.read())
            assertEquals(first, store.read())

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue(
                keyStore.containsAlias("${context.packageName}.cat.server.credentials.v1")
            )

            val second = credential("second-device", "second-token")
            store.write(second)
            assertEquals(second, store.read())

            // Simulate an interrupted AtomicFile commit: only the backup remains. openRead() must
            // promote it before decrypting instead of treating the credential as absent.
            val backup = File("${credentialFile.path}.bak")
            assertTrue(credentialFile.renameTo(backup))
            assertEquals(second, store.read())
            assertFalse(backup.exists())
            assertTrue(credentialFile.isFile)

            // Simulate a damaged local payload. read() must discard the unusable file and key;
            // the next write must recreate the complete Keystore-backed unit.
            credentialFile.writeBytes(byteArrayOf(1, 12, 0, 1, 2, 3))
            assertNull(store.read())
            assertFalse(credentialFile.exists())
            assertFalse(backup.exists())
            assertNotNull(store.lastFailure())
            assertEquals("DECRYPT_FAILED", store.lastFailure()?.code)

            val recovered = credential("recovered-device", "recovered-token")
            store.write(recovered)
            assertEquals(recovered, store.read())
        } finally {
            store.clear()
        }
    }

    private fun credential(deviceId: String, token: String) =
        CatServerCredentials(
            deviceToken = token,
            certificateFingerprint = "sha256:${"a".repeat(64)}",
            deviceId = deviceId,
        )
}

package com.zaneschepke.wireguardautotunnel.data.cat

import com.dedtsss.catawg.core.protocol.CatServerCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatCredentialEnvelopeCodecTest {
    @Test
    fun `credential envelope round trips normalized identity`() {
        val credentials =
            CatServerCredentials(
                deviceToken = "device-token-secret",
                certificateFingerprint =
                    "SHA-256:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                deviceId = "device-1",
            )

        val restored =
            CatCredentialEnvelopeCodec.decode(CatCredentialEnvelopeCodec.encode(credentials))

        assertEquals("device-token-secret", restored.deviceToken)
        assertEquals(
            "sha256:aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            restored.certificateFingerprint,
        )
        assertEquals("device-1", restored.deviceId)
    }

    @Test
    fun `credential value string representation redacts the device token`() {
        val credentials =
            CatServerCredentials(
                deviceToken = "device-token-secret",
                certificateFingerprint = "sha256:${"0".repeat(64)}",
                deviceId = "device-1",
            )

        assertFalse(credentials.toString().contains(credentials.deviceToken))
        assertTrue(credentials.toString().contains("[REDACTED]"))
    }
}

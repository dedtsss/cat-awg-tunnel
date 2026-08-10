package com.dedtsss.catawg.core.protocol

import com.dedtsss.catawg.core.ai.AiAssistantRequest
import com.dedtsss.catawg.core.ai.AiAssistantResponse
import com.dedtsss.catawg.core.ai.DisabledCatAiProvider
import com.dedtsss.catawg.core.ai.StaticCatAiProvider
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatProtocolContractsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/v1/fixtures/$name")) {
                "Missing canonical fixture $name"
            }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `canonical server fixtures decode into Android models`() {
        val health = json.decodeFromString<CatHealth>(fixture("health.json"))
        val capabilities =
            json.decodeFromString<ServerCapabilities>(fixture("server_capabilities.json"))
        val upload =
            json.decodeFromString<DiagnosticUploadRequest>(fixture("diagnostic_upload.json"))
        val stored = json.decodeFromString<DiagnosticEvent>(fixture("diagnostic_event.json"))
        val validation =
            json.decodeFromString<ConfigValidationResponse>(fixture("config_validation.json"))
        val incidents = json.decodeFromString<IncidentsResponse>(fixture("incidents_response.json"))
        val aiRequest = json.decodeFromString<CatAiChatRequest>(fixture("ai_chat_request.json"))
        val aiResponse = json.decodeFromString<CatAiChatResponse>(fixture("ai_chat_response.json"))
        val pairing =
            json.decodeFromString<PairingCompleteResponse>(fixture("pairing_complete.json"))

        assertEquals(CatProtocolV1.SCHEMA_VERSION, health.schemaVersion)
        assertEquals("ok", health.status)
        assertTrue(
            capabilities.engines.getValue("awg2").supported.not() ||
                capabilities.engines.containsKey("awg2")
        )
        assertEquals("42", upload.events.single().tunnelId)
        assertEquals("NETWORK_LOST", stored.code)
        assertTrue(validation.publicProfile.validationSummary.valid)
        assertTrue(incidents.incidents.isEmpty())
        assertEquals("Diagnose DNS", aiRequest.message)
        assertTrue(aiResponse.candidateOnly)
        assertFalse(aiResponse.applied)
        assertEquals("Pixel", pairing.device.name)
    }

    @Test
    fun `unknown future capability is ignored but unknown upload fields are not modeled`() {
        val capabilities =
            json.decodeFromString<ServerCapabilities>(
                fixture("server_capabilities.json")
                    .replace("\"features\":", "\"futureCapability\":true,\"features\":")
            )
        assertTrue(capabilities.features.diagnostics)
        val upload =
            json.decodeFromString<DiagnosticUploadRequest>(fixture("diagnostic_upload.json"))
        assertEquals("CLIENT", upload.events.single().source.name)
    }

    @Test
    fun `mock boundary exposes the same envelopes without credentials`() = runBlocking {
        val server = InMemoryCatServerClient()
        assertEquals("ok", server.health().status)
        assertFalse(server.capabilities().engines.getValue("awg3").supported)
        assertTrue(
            server
                .incidents(TimeRange("2026-08-08T00:00:00Z", "2026-08-08T01:00:00Z"))
                .incidents
                .isEmpty()
        )
        assertNull(server.aiChat(CatAiChatRequest(message = "diagnose")))
        assertFalse(DisabledCatAiProvider.available)
        assertNull(DisabledCatAiProvider.ask(AiAssistantRequest("diagnose")))
        assertTrue(StaticCatAiProvider(AiAssistantResponse("candidate only")).available)
    }

    @Test
    fun `endpoint names remain under the versioned prefix`() {
        val endpoints =
            listOf(
                CatProtocolV1.HEALTH,
                CatProtocolV1.CAPABILITIES,
                CatProtocolV1.PAIRING_START,
                CatProtocolV1.PAIRING_COMPLETE,
                CatProtocolV1.DIAGNOSTIC_EVENTS,
                CatProtocolV1.INCIDENTS,
                CatProtocolV1.DIAGNOSTIC_BUNDLE,
                CatProtocolV1.CONFIG_VALIDATE,
                CatProtocolV1.METRICS_COMPARE,
                CatProtocolV1.AI_CHAT,
            )
        assertTrue(endpoints.all { it.startsWith("/api/v1/") })
    }

    @Test
    fun `bootstrap parser normalizes fingerprints and never exposes token in text form`() {
        val payload =
            CatBootstrapParser.parse(
                """
                catpair:v1
                server=https://cat.example:8443
                fingerprint=SHA-256:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99
                token=one-time-secret
                """
                    .trimIndent()
            )
        assertEquals("https://cat.example:8443", payload.server)
        assertEquals(
            "sha256:aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            payload.certificateFingerprint,
        )
        assertTrue(payload.toString().contains("REDACTED"))
        assertFalse(payload.toString().contains("one-time-secret"))
    }

    @Test
    fun `public config validation rejects secret-bearing keys`() {
        assertTrue(isSecretBearingConfigKey("Private-Key"))
        assertTrue(isSecretBearingConfigKey("authorization"))
    }

    @Test
    fun `management URL normalization rejects insecure or stateful origins`() {
        assertEquals("https://cat.example:8443", normalizeCatServerUrl("https://cat.example:8443/"))
        assertTrue(runCatching { normalizeCatServerUrl("http://cat.example:8443") }.isFailure)
        assertTrue(runCatching { normalizeCatServerUrl("https://user:pass@cat.example") }.isFailure)
        assertTrue(runCatching { normalizeCatServerUrl("https://cat.example/api/v1") }.isFailure)
        assertTrue(
            runCatching { normalizeCatServerUrl("https://cat.example?token=secret") }.isFailure
        )
    }

    @Test
    fun `bootstrap URI round trips encoded token without leaking it in toString`() {
        val payload =
            CatBootstrapPayload(
                server = "https://cat.example:8443",
                certificateFingerprint = "sha256:${"a".repeat(64)}",
                bootstrapToken = "one-time+secret&value",
            )

        val deepLink = CatBootstrapParser.toDeepLink(payload)
        val restored = CatBootstrapParser.parse(deepLink)

        assertEquals(payload, restored)
        assertTrue(deepLink.startsWith("catpair:v1?"))
        assertFalse(restored.toString().contains(payload.bootstrapToken))
    }

    @Test
    fun `bootstrap parser accepts legacy QR URI but rejects ambiguous deep link fields`() {
        val fingerprint = "sha256:${"b".repeat(64)}"
        val legacy =
            "cat://pair?server=https%3A%2F%2Fcat.example&fingerprint=$fingerprint&token=one-time"

        assertEquals("https://cat.example", CatBootstrapParser.parse(legacy).server)
        assertTrue(
            runCatching {
                    CatBootstrapParser.parse(
                        "catpair:v1?server=https%3A%2F%2Fcat.example&server=https%3A%2F%2Fother.example&fingerprint=$fingerprint&token=one-time"
                    )
                }
                .isFailure
        )
    }
}

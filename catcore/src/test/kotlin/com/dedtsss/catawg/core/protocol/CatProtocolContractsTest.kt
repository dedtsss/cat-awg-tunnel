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
        requireNotNull(javaClass.getResourceAsStream("/v1/fixtures/$name")) { "Missing canonical fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun `canonical server fixtures decode into Android models`() {
        val health = json.decodeFromString<CatHealth>(fixture("health.json"))
        val capabilities = json.decodeFromString<ServerCapabilities>(fixture("server_capabilities.json"))
        val upload = json.decodeFromString<DiagnosticUploadRequest>(fixture("diagnostic_upload.json"))
        val stored = json.decodeFromString<DiagnosticEvent>(fixture("diagnostic_event.json"))
        val validation = json.decodeFromString<ConfigValidationResponse>(fixture("config_validation.json"))
        val incidents = json.decodeFromString<IncidentsResponse>(fixture("incidents_response.json"))
        val aiRequest = json.decodeFromString<CatAiChatRequest>(fixture("ai_chat_request.json"))
        val aiResponse = json.decodeFromString<CatAiChatResponse>(fixture("ai_chat_response.json"))
        val pairing = json.decodeFromString<PairingCompleteResponse>(fixture("pairing_complete.json"))

        assertEquals(CatProtocolV1.SCHEMA_VERSION, health.schemaVersion)
        assertEquals("ok", health.status)
        assertTrue(capabilities.engines.getValue("awg2").supported.not() || capabilities.engines.containsKey("awg2"))
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
        val capabilities = json.decodeFromString<ServerCapabilities>(fixture("server_capabilities.json").replace("\"features\":", "\"futureCapability\":true,\"features\":"))
        assertTrue(capabilities.features.diagnostics)
        val upload = json.decodeFromString<DiagnosticUploadRequest>(fixture("diagnostic_upload.json"))
        assertEquals("CLIENT", upload.events.single().source.name)
    }

    @Test
    fun `mock boundary exposes the same envelopes without credentials`() = runBlocking {
        val server = InMemoryCatServerClient()
        assertEquals("ok", server.health().status)
        assertFalse(server.capabilities().engines.getValue("awg3").supported)
        assertTrue(server.incidents(TimeRange("2026-08-08T00:00:00Z", "2026-08-08T01:00:00Z")).incidents.isEmpty())
        assertNull(server.aiChat(CatAiChatRequest(message = "diagnose")))
        assertFalse(DisabledCatAiProvider.available)
        assertNull(DisabledCatAiProvider.ask(AiAssistantRequest("diagnose")))
        assertTrue(StaticCatAiProvider(AiAssistantResponse("candidate only")).available)
    }

    @Test
    fun `endpoint names remain under the versioned prefix`() {
        val endpoints = listOf(CatProtocolV1.HEALTH, CatProtocolV1.CAPABILITIES, CatProtocolV1.PAIRING_START, CatProtocolV1.PAIRING_COMPLETE, CatProtocolV1.DIAGNOSTIC_EVENTS, CatProtocolV1.INCIDENTS, CatProtocolV1.DIAGNOSTIC_BUNDLE, CatProtocolV1.CONFIG_VALIDATE, CatProtocolV1.AI_CHAT)
        assertTrue(endpoints.all { it.startsWith("/api/v1/") })
    }
}

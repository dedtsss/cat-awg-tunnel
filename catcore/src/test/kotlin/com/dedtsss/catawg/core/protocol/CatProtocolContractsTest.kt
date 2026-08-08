package com.dedtsss.catawg.core.protocol

import com.dedtsss.catawg.core.ai.DisabledCatAiProvider
import com.dedtsss.catawg.core.ai.StaticCatAiProvider
import com.dedtsss.catawg.core.ai.AiAssistantRequest
import com.dedtsss.catawg.core.ai.AiAssistantResponse
import com.dedtsss.catawg.core.configurator.PublicConfigProfile
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import com.dedtsss.catawg.core.diagnostics.Incident
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
        requireNotNull(javaClass.getResourceAsStream("/v1/$name")) { "Missing contract fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `versioned contract fixtures decode to the client models`() {
        val capabilities = json.decodeFromString<ServerCapabilities>(fixture("server-capabilities.json"))
        val event = json.decodeFromString<DiagnosticEvent>(fixture("diagnostic-event.json"))
        val incident = json.decodeFromString<Incident>(fixture("incident.json"))
        val profile = json.decodeFromString<PublicConfigProfile>(fixture("public-config-profile.json"))

        assertEquals(CatProtocolV1.SCHEMA_VERSION, capabilities.schemaVersion)
        assertTrue(capabilities.engines.getValue("awg2").supported)
        assertEquals("DNS_RESOLUTION_FAILED", event.code)
        assertEquals("DNS_FAILURE", incident.classification)
        assertFalse(profile.parameters.keys.any { it.contains("key", ignoreCase = true) })
    }

    @Test
    fun `mock server and AI boundaries are functional without credentials`() = runBlocking {
        val server = InMemoryCatServerClient()
        assertEquals("ok", server.health().status)
        assertFalse(server.capabilities().engines.getValue("awg3").supported)
        assertNull(server.aiChat(CatAiChatRequest(message = "diagnose")))

        assertFalse(DisabledCatAiProvider.available)
        assertNull(DisabledCatAiProvider.ask(AiAssistantRequest("diagnose")))
        val static = StaticCatAiProvider(AiAssistantResponse("candidate only"))
        assertTrue(static.available)
        assertEquals("candidate only", static.ask(AiAssistantRequest("diagnose"))?.message)
    }

    @Test
    fun `endpoint names remain additive under the v1 prefix`() {
        val endpoints =
            listOf(
                CatProtocolV1.HEALTH,
                CatProtocolV1.CAPABILITIES,
                CatProtocolV1.DIAGNOSTIC_EVENTS,
                CatProtocolV1.INCIDENTS,
                CatProtocolV1.DIAGNOSTIC_BUNDLE,
                CatProtocolV1.CONFIG_VALIDATE,
                CatProtocolV1.AI_CHAT,
            )
        assertTrue(endpoints.all { it.startsWith("/api/v1/") })
    }
}

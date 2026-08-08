package com.dedtsss.catawg.core.diagnostics

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val start = Instant.parse("2026-08-08T10:00:00Z")

    private fun event(
        code: String,
        at: Instant = start,
        category: DiagnosticCategory = DiagnosticCategory.NETWORK,
    ) =
        DiagnosticEvent(
            id = "$code-${at.epochSecond}",
            ts = at.toString(),
            category = category,
            code = code,
            summary = "$code observed",
        )

    @Test
    fun `detector records transient interruption as resolved info and repeated recovery failure as warning`() {
        val transient =
            IncidentDetector().detect(
                listOf(event("NETWORK_LOST"), event("NETWORK_AVAILABLE", start.plusSeconds(30))),
                now = start.plusSeconds(31),
            )
        assertEquals("UNDERLYING_NETWORK_INTERRUPTION", transient.single().classification)
        assertEquals(IncidentSeverity.INFO, transient.single().severity)
        assertEquals(IncidentStatus.RESOLVED, transient.single().status)

        val reconnectLoop =
            IncidentDetector().detect(
                (1..3).map { event("TUNNEL_RECONNECT_FAILED", start.plusSeconds(it.toLong())) },
                now = start.plusSeconds(10),
            )
        assertTrue(reconnectLoop.any { it.classification == "RECONNECT_LOOP" && it.severity == IncidentSeverity.WARNING })
    }

    @Test
    fun `handshake age by itself does not create a false failure incident`() {
        val incidents =
            IncidentDetector().detect(
                listOf(event("HANDSHAKE_OLD", category = DiagnosticCategory.TUNNEL)),
                now = start.plusSeconds(5),
            )
        assertTrue(incidents.isEmpty())
    }

    @Test
    fun `recorder sanitizes retains and closes open incidents after healthy window`() = runBlocking {
        val store = InMemoryDiagnosticStore()
        val recorder = ClientDiagnosticRecorder(store)
        val unsafe =
            event("NETWORK_LOST").copy(
                summary = "PrivateKey = not-for-export",
                details = mapOf("apiToken" to "also-not-for-export"),
            )
        recorder.record(unsafe, now = start)
        val saved = store.events(Instant.EPOCH, start).single()
        assertTrue(saved.summary.contains("[REDACTED]"))
        assertEquals("[REDACTED]", saved.details.getValue("apiToken"))
        assertTrue(store.incidents(Instant.EPOCH, start).any { it.status == IncidentStatus.OPEN })

        val afterWindow = start.plusSeconds(16 * 60)
        recorder.record(event("HEALTHY_HEARTBEAT", afterWindow, DiagnosticCategory.SYSTEM), now = afterWindow)
        assertTrue(store.incidents(Instant.EPOCH, afterWindow).any { it.status == IncidentStatus.RESOLVED })

        store.record(event("OLD", start.minusSeconds(49 * 60 * 60)))
        recorder.record(event("RETENTION_TICK", afterWindow, DiagnosticCategory.SYSTEM), now = afterWindow)
        assertFalse(store.events(Instant.EPOCH, afterWindow).any { it.code == "OLD" })
    }

    @Test
    fun `export has server-merge layout and never leaks known secrets`() {
        val bundle =
            DiagnosticExportBuilder.build(
                events = listOf(event("DNS_RESOLUTION_FAILED", category = DiagnosticCategory.DNS)),
                incidents = emptyList(),
                from = start.minusSeconds(15 * 60),
                to = start,
                sanitizedLogs = "PresharedKey: not-for-export\nnormal log line",
            )

        assertEquals(
            setOf(
                "manifest.json",
                "client/events.jsonl",
                "client/logs.txt",
                "client/network.jsonl",
                "client/incidents.json",
            ),
            bundle.entries.keys,
        )
        assertTrue(bundle.entries.getValue("client/logs.txt").contains("[REDACTED]"))
        assertFalse(bundle.entries.values.joinToString("\n").contains("not-for-export"))
    }
}

private class InMemoryDiagnosticStore : DiagnosticStore {
    private val eventEntries = mutableListOf<DiagnosticEvent>()
    private val incidentEntries = linkedMapOf<String, Incident>()

    override suspend fun record(event: DiagnosticEvent) {
        eventEntries.removeAll { it.id == event.id }
        eventEntries += event
    }

    override suspend fun events(from: Instant, to: Instant): List<DiagnosticEvent> =
        eventEntries.filter { Instant.parse(it.ts) in from..to }.sortedBy { it.ts }

    override suspend fun upsertIncident(incident: Incident) {
        incidentEntries[incident.id] = incident
    }

    override suspend fun incidents(from: Instant, to: Instant): List<Incident> =
        incidentEntries.values.filter { Instant.parse(it.startAt) in from..to }.sortedBy { it.startAt }

    override suspend fun retainSince(cutoff: Instant) {
        eventEntries.removeAll { Instant.parse(it.ts).isBefore(cutoff) }
        incidentEntries.values.removeAll { Instant.parse(it.startAt).isBefore(cutoff) }
    }
}

package com.dedtsss.catawg.core.diagnostics

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class DiagnosticSource {
    CLIENT,
    SERVER,
}

@Serializable
enum class DiagnosticCategory {
    NETWORK,
    TUNNEL,
    DNS,
    ROUTE,
    SYSTEM,
    PROBE,
    CONFIG,
}

@Serializable
enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * Structured event content is intentionally string-only and sanitized at the recording boundary.
 */
@Serializable
data class DiagnosticEvent(
    val id: String = UUID.randomUUID().toString(),
    val ts: String = Instant.now().toString(),
    val source: DiagnosticSource = DiagnosticSource.CLIENT,
    val category: DiagnosticCategory,
    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
    val code: String,
    val summary: String,
    val details: Map<String, String> = emptyMap(),
    /** Opaque wire identifier; Android database ids are converted to strings at this boundary. */
    val tunnelId: String? = null,
)

@Serializable
enum class IncidentSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

@Serializable
enum class IncidentStatus {
    OPEN,
    RESOLVED,
    OBSERVING,
}

@Serializable
data class Incident(
    val id: String,
    val startAt: String,
    val endAt: String? = null,
    val severity: IncidentSeverity,
    val status: IncidentStatus =
        if (endAt == null) IncidentStatus.OPEN else IncidentStatus.RESOLVED,
    val classification: String,
    val confidence: Double,
    val eventIds: List<String>,
    val probableCause: String? = null,
    val recommendations: List<String> = emptyList(),
)

interface DiagnosticStore {
    suspend fun record(event: DiagnosticEvent)

    suspend fun events(from: Instant, to: Instant = Instant.now()): List<DiagnosticEvent>

    suspend fun upsertIncident(incident: Incident)

    suspend fun incidents(from: Instant, to: Instant = Instant.now()): List<Incident>

    suspend fun retainSince(cutoff: Instant)
}

object DiagnosticSanitizer {
    private val secretFragments =
        listOf(
            "privatekey",
            "private_key",
            "presharedkey",
            "preshared_key",
            "password",
            "token",
            "api_key",
            "apikey",
            "secret",
            "ssh_key",
        )
    private val assignment =
        Regex(
            """(?i)\b(private[_ -]?key|preshared[_ -]?key|password|token|api[_ -]?key|secret)\s*[:=]\s*[^\s,;]+"""
        )

    fun sanitize(event: DiagnosticEvent): DiagnosticEvent {
        return event.copy(
            summary = sanitizeText(event.summary),
            details =
                event.details.mapValues { (key, value) ->
                    if (isSecretKey(key)) "[REDACTED]" else sanitizeText(value)
                },
        )
    }

    fun sanitizeText(text: String): String =
        assignment.replace(text) { match -> "${match.groupValues[1]}=[REDACTED]" }

    fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase().replace(Regex("[^a-z0-9]"), "")
        return secretFragments.any { fragment -> normalized.contains(fragment.replace("_", "")) }
    }
}

/**
 * Deterministic, conservative incident correlation. A handshake age observation is deliberately
 * excluded from failure classification unless another failure signal is present.
 */
class IncidentDetector {
    fun detect(events: List<DiagnosticEvent>, now: Instant = Instant.now()): List<Incident> {
        val ordered = events.sortedBy { it.ts }
        val recent = ordered.filter {
            parseInstant(it.ts)?.isAfter(now.minus(15, ChronoUnit.MINUTES)) == true
        }
        val detected = mutableListOf<Incident>()

        fun incident(
            classification: String,
            severity: IncidentSeverity,
            evidence: List<DiagnosticEvent>,
            confidence: Double,
            cause: String,
            recommendations: List<String>,
            endAt: String? = null,
        ) {
            if (evidence.isEmpty()) return
            val start = evidence.minBy { it.ts }.ts
            val key = "$classification:${evidence.minBy { it.ts }.id}"
            detected +=
                Incident(
                    id = UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString(),
                    startAt = start,
                    endAt = endAt,
                    severity = severity,
                    status = if (endAt == null) IncidentStatus.OPEN else IncidentStatus.RESOLVED,
                    classification = classification,
                    confidence = confidence.coerceIn(0.0, 1.0),
                    eventIds = evidence.map { it.id },
                    probableCause = cause,
                    recommendations = recommendations,
                )
        }

        val networkLost = recent.filter { it.code == "NETWORK_LOST" }
        val latestNetworkAvailable = recent.lastOrNull { it.code == "NETWORK_AVAILABLE" }
        val outstandingNetworkLoss = networkLost.filter { loss ->
            latestNetworkAvailable == null || loss.ts > latestNetworkAvailable.ts
        }
        val recoveredNetworkLoss = networkLost.filter { loss ->
            latestNetworkAvailable != null && loss.ts <= latestNetworkAvailable.ts
        }
        if (outstandingNetworkLoss.isNotEmpty()) {
            incident(
                classification = "UNDERLYING_NETWORK_LOST",
                severity = IncidentSeverity.INFO,
                evidence = outstandingNetworkLoss,
                confidence = 0.9,
                cause = "Android reported loss of the underlying network.",
                recommendations =
                    listOf("Check Wi-Fi/mobile connectivity before changing tunnel settings."),
            )
        }
        if (recoveredNetworkLoss.isNotEmpty() && latestNetworkAvailable != null) {
            incident(
                classification = "UNDERLYING_NETWORK_INTERRUPTION",
                severity = IncidentSeverity.INFO,
                evidence = recoveredNetworkLoss + latestNetworkAvailable,
                confidence = 0.9,
                cause = "Android reported a temporary underlying-network interruption.",
                recommendations =
                    listOf("No tunnel setting change is indicated unless interruptions repeat."),
                endAt = latestNetworkAvailable.ts,
            )
        }
        if (networkLost.size >= 3) {
            incident(
                classification = "REPEATED_NETWORK_INTERRUPTION",
                severity = IncidentSeverity.WARNING,
                evidence = networkLost,
                confidence = 0.8,
                cause = "Several underlying-network losses were observed in a short interval.",
                recommendations =
                    listOf("Check Wi-Fi/mobile stability before changing tunnel settings."),
                endAt = latestNetworkAvailable?.ts,
            )
        }

        val reconnectFailures = recent.filter {
            it.code in setOf("TUNNEL_RECONNECT_FAILED", "TUNNEL_BOUNCE_FAILED")
        }
        if (reconnectFailures.size >= 3) {
            incident(
                classification = "RECONNECT_LOOP",
                severity = IncidentSeverity.WARNING,
                evidence = reconnectFailures,
                confidence = 0.85,
                cause = "Several tunnel recovery attempts failed while the app was running.",
                recommendations = listOf("Inspect endpoint reachability and the selected network."),
            )
        }

        val fatalRecovery = recent.filter { it.code == "TUNNEL_RECOVERY_EXHAUSTED" }
        if (fatalRecovery.isNotEmpty() && recent.any { it.code == "NETWORK_AVAILABLE" }) {
            incident(
                classification = "TUNNEL_FAILED_TO_RECOVER",
                severity = IncidentSeverity.CRITICAL,
                evidence =
                    fatalRecovery + recent.filter { it.code == "NETWORK_AVAILABLE" }.takeLast(1),
                confidence = 0.8,
                cause =
                    "An available underlying network was observed, but tunnel recovery was exhausted.",
                recommendations =
                    listOf("Export diagnostics and verify endpoint/configuration availability."),
            )
        }

        val dnsFailures = recent.filter { it.code == "DNS_RESOLUTION_FAILED" }
        if (dnsFailures.size >= 2) {
            incident(
                classification = "DNS_FAILURE",
                severity = IncidentSeverity.WARNING,
                evidence = dnsFailures,
                confidence = 0.75,
                cause = "Repeated DNS resolution failures were recorded.",
                recommendations =
                    listOf("Refresh the domain rule after checking direct-network DNS."),
            )
        }

        val ipv6Mismatch = recent.filter { it.code == "IP_FAMILY_MISMATCH" }
        if (ipv6Mismatch.isNotEmpty()) {
            incident(
                classification = "IPV4_IPV6_MISMATCH",
                severity = IncidentSeverity.WARNING,
                evidence = ipv6Mismatch,
                confidence = 0.65,
                cause = "A route or resolver reported incompatible IPv4/IPv6 availability.",
                recommendations = listOf("Compare IPv4 and IPv6 results in site diagnostics."),
            )
        }
        return detected
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
}

class ClientDiagnosticRecorder(
    private val store: DiagnosticStore,
    private val detector: IncidentDetector = IncidentDetector(),
    private val retention: java.time.Duration = java.time.Duration.ofHours(48),
) {
    suspend fun record(event: DiagnosticEvent, now: Instant = Instant.now()) {
        store.record(DiagnosticSanitizer.sanitize(event))
        store.retainSince(now.minus(retention))
        val from = now.minus(15, ChronoUnit.MINUTES)
        val detected = detector.detect(store.events(from, now), now)
        // Keep open incidents visible for the full retention period so a later healthy signal can
        // close a short interruption even after the 15-minute detector window has rolled over.
        val existing = store.incidents(now.minus(retention), now)
        existing
            .filter {
                it.status == IncidentStatus.OPEN &&
                    detected.none { candidate -> candidate.classification == it.classification }
            }
            .forEach { open ->
                store.upsertIncident(
                    open.copy(endAt = now.toString(), status = IncidentStatus.RESOLVED)
                )
            }
        detected.forEach { candidate ->
            val prior = existing.firstOrNull {
                it.classification == candidate.classification && it.status == IncidentStatus.OPEN
            }
            store.upsertIncident(
                if (prior == null) candidate
                else candidate.copy(id = prior.id, startAt = prior.startAt)
            )
        }
    }
}

@Serializable
enum class DiagnosticExportWindow(val minutes: Long) {
    MINUTES_15(15),
    HOUR_1(60),
    HOURS_6(360),
    HOURS_24(1440),
}

@Serializable
data class DiagnosticExportManifest(
    val schemaVersion: String = "cat.diagnostics.v1",
    val generatedAt: String,
    val from: String,
    val to: String,
    val entries: List<String>,
    val sanitized: Boolean = true,
)

data class DiagnosticExportBundle(val entries: Map<String, String>)

object DiagnosticExportBuilder {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun build(
        events: List<DiagnosticEvent>,
        incidents: List<Incident>,
        from: Instant,
        to: Instant = Instant.now(),
        sanitizedLogs: String = "",
    ): DiagnosticExportBundle {
        val cleanedEvents = events.map(DiagnosticSanitizer::sanitize)
        val manifest =
            DiagnosticExportManifest(
                generatedAt = to.toString(),
                from = from.toString(),
                to = to.toString(),
                entries =
                    listOf(
                        "manifest.json",
                        "client/events.jsonl",
                        "client/logs.txt",
                        "client/network.jsonl",
                        "client/incidents.json",
                    ),
            )
        val eventsJsonl = cleanedEvents.joinToString("\n") { json.encodeToString(it) }
        val networkJsonl =
            cleanedEvents
                .filter { it.category == DiagnosticCategory.NETWORK }
                .joinToString("\n") { json.encodeToString(it) }
        return DiagnosticExportBundle(
            mapOf(
                "manifest.json" to json.encodeToString(manifest),
                "client/events.jsonl" to eventsJsonl,
                "client/logs.txt" to DiagnosticSanitizer.sanitizeText(sanitizedLogs),
                "client/network.jsonl" to networkJsonl,
                "client/incidents.json" to json.encodeToString(incidents),
            )
        )
    }
}

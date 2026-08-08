package com.zaneschepke.wireguardautotunnel.data.repository

import com.dedtsss.catawg.core.diagnostics.DiagnosticCategory
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import com.dedtsss.catawg.core.diagnostics.DiagnosticSeverity
import com.dedtsss.catawg.core.diagnostics.DiagnosticSource
import com.dedtsss.catawg.core.diagnostics.DiagnosticStore
import com.dedtsss.catawg.core.diagnostics.Incident
import com.dedtsss.catawg.core.diagnostics.IncidentSeverity
import com.dedtsss.catawg.core.diagnostics.IncidentStatus
import com.zaneschepke.wireguardautotunnel.data.dao.CatDiagnosticsDao
import com.zaneschepke.wireguardautotunnel.data.entity.CatDiagnosticEvent
import com.zaneschepke.wireguardautotunnel.data.entity.CatIncident
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomDiagnosticStore(
    private val dao: CatDiagnosticsDao,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : DiagnosticStore {
    override suspend fun record(event: DiagnosticEvent) {
        dao.upsertEvent(event.toEntity())
    }

    override suspend fun events(from: Instant, to: Instant): List<DiagnosticEvent> =
        dao.events(from.toString(), to.toString()).map(::toDomain)

    override suspend fun upsertIncident(incident: Incident) {
        dao.upsertIncident(incident.toEntity())
    }

    override suspend fun incidents(from: Instant, to: Instant): List<Incident> =
        dao.incidents(from.toString(), to.toString()).map(::toDomain)

    override suspend fun retainSince(cutoff: Instant) {
        dao.trimEvents(cutoff.toString())
        dao.trimIncidents(cutoff.toString())
    }

    private fun DiagnosticEvent.toEntity() =
        CatDiagnosticEvent(
            id = id,
            ts = ts,
            source = source.name,
            category = category.name,
            severity = severity.name,
            code = code,
            summary = summary,
            details_json = json.encodeToString(details),
            tunnel_id = tunnelId,
        )

    private fun toDomain(event: CatDiagnosticEvent) =
        DiagnosticEvent(
            id = event.id,
            ts = event.ts,
            source = enumOr(event.source, DiagnosticSource.CLIENT),
            category = enumOr(event.category, DiagnosticCategory.SYSTEM),
            severity = enumOr(event.severity, DiagnosticSeverity.INFO),
            code = event.code,
            summary = event.summary,
            details = runCatching { json.decodeFromString<Map<String, String>>(event.details_json) }.getOrDefault(emptyMap()),
            tunnelId = event.tunnel_id,
        )

    private fun Incident.toEntity() =
        CatIncident(
            id = id,
            start_at = startAt,
            end_at = endAt,
            severity = severity.name,
            status = status.name,
            classification = classification,
            confidence = confidence,
            event_ids_json = json.encodeToString(eventIds),
            probable_cause = probableCause,
            recommendations_json = json.encodeToString(recommendations),
        )

    private fun toDomain(incident: CatIncident) =
        Incident(
            id = incident.id,
            startAt = incident.start_at,
            endAt = incident.end_at,
            severity = enumOr(incident.severity, IncidentSeverity.INFO),
            status = enumOr(incident.status, IncidentStatus.OPEN),
            classification = incident.classification,
            confidence = incident.confidence,
            eventIds = runCatching { json.decodeFromString<List<String>>(incident.event_ids_json) }.getOrDefault(emptyList()),
            probableCause = incident.probable_cause,
            recommendations = runCatching { json.decodeFromString<List<String>>(incident.recommendations_json) }.getOrDefault(emptyList()),
        )

    private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

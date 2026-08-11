package com.zaneschepke.wireguardautotunnel.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zaneschepke.wireguardautotunnel.data.entity.CatDiagnosticEvent
import com.zaneschepke.wireguardautotunnel.data.entity.CatIncident

@Dao
interface CatDiagnosticsDao {
    @Upsert suspend fun upsertEvent(event: CatDiagnosticEvent)

    @Query("SELECT * FROM cat_diagnostic_events WHERE ts >= :from AND ts <= :to ORDER BY ts")
    suspend fun events(from: String, to: String): List<CatDiagnosticEvent>

    @Query("DELETE FROM cat_diagnostic_events WHERE ts < :cutoff") suspend fun trimEvents(cutoff: String)

    @Upsert suspend fun upsertIncident(incident: CatIncident)

    @Query("SELECT * FROM cat_incidents WHERE start_at >= :from AND start_at <= :to ORDER BY start_at")
    suspend fun incidents(from: String, to: String): List<CatIncident>

    @Query("DELETE FROM cat_incidents WHERE start_at < :cutoff") suspend fun trimIncidents(cutoff: String)
}

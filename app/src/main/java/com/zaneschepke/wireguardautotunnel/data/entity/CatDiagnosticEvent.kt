package com.zaneschepke.wireguardautotunnel.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cat_diagnostic_events",
    indices = [Index(value = ["ts"]), Index(value = ["tunnel_id"])],
)
data class CatDiagnosticEvent(
    @PrimaryKey val id: String,
    val ts: String,
    val source: String,
    val category: String,
    val severity: String,
    val code: String,
    val summary: String,
    val details_json: String,
    val tunnel_id: Int?,
)

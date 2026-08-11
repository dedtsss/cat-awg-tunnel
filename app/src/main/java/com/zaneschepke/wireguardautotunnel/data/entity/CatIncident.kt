package com.zaneschepke.wireguardautotunnel.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cat_incidents", indices = [Index(value = ["start_at"])])
data class CatIncident(
    @PrimaryKey val id: String,
    val start_at: String,
    val end_at: String?,
    val severity: String,
    val status: String,
    val classification: String,
    val confidence: Double,
    val event_ids_json: String,
    val probable_cause: String?,
    val recommendations_json: String,
)

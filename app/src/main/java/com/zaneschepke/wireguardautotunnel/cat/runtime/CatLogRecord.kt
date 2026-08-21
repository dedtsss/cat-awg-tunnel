package com.zaneschepke.wireguardautotunnel.cat.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CatLogRecord(
    val timestampMs: Long,
    val elapsedMs: Long,
    val sessionId: String,
    val correlationId: String,
    val component: String,
    val eventType: String,
    val action: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val result: String? = null,
    val evidence: String? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    fun toNdjson(json: Json = CODEC): String = json.encodeToString(this) + "\n"

    companion object {
        val CODEC = Json { encodeDefaults = true; explicitNulls = false }
    }
}

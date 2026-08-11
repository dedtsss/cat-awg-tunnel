package com.dedtsss.catawg.core.protocol

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Copy/paste and QR-compatible pairing material. It contains only the server origin, certificate
 * fingerprint and one-time bootstrap token; no device credential is included.
 */
@Serializable
data class CatBootstrapPayload(
    val server: String,
    val certificateFingerprint: String,
    val bootstrapToken: String,
) {
    fun normalized(): CatBootstrapPayload =
        copy(
            server = normalizeCatServerUrl(server),
            certificateFingerprint = normalizeCertificateFingerprint(certificateFingerprint),
        )

    fun encode(): String {
        val normalized = normalized()
        return buildString {
            appendLine("catpair:v1")
            appendLine("server=${normalized.server}")
            appendLine("fingerprint=${normalized.certificateFingerprint}")
            append("token=${normalized.bootstrapToken}")
        }
    }

    override fun toString(): String =
        "CatBootstrapPayload(server=$server, certificateFingerprint=$certificateFingerprint, bootstrapToken=[REDACTED])"
}

object CatBootstrapParser {
    private val json = Json { ignoreUnknownKeys = true }

    const val PAIRING_SCHEME = "catpair"
    const val PAIRING_VERSION = "v1"
    private const val PAIRING_PREFIX = "$PAIRING_SCHEME:$PAIRING_VERSION"

    fun parse(raw: String): CatBootstrapPayload {
        val value = raw.trim()
        require(value.isNotBlank()) { "Bootstrap payload is empty" }
        val payload =
            when {
                value.startsWith("{") -> json.decodeFromString<CatBootstrapPayload>(value)
                isMultilinePayload(value) -> parseText(value)
                value.startsWith("$PAIRING_SCHEME:", ignoreCase = true) -> parseDeepLink(value)
                value.startsWith("cat://", ignoreCase = true) -> parseUri(value)
                else -> parseText(value)
            }
        require(payload.bootstrapToken.isNotBlank()) {
            "Bootstrap payload is missing the one-time token"
        }
        return payload.normalized()
    }

    private fun isMultilinePayload(value: String): Boolean =
        value.lineSequence().firstOrNull()?.equals(PAIRING_PREFIX, ignoreCase = true) == true &&
            value.contains('\n')

    private fun parseText(value: String): CatBootstrapPayload {
        val lines = value.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(lines.firstOrNull()?.equals("catpair:v1", ignoreCase = true) == true) {
            "Unsupported bootstrap format; expected catpair:v1"
        }
        val entries = buildMap {
            lines.drop(1).forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Bootstrap fields must use key=value lines" }
                val key = line.substring(0, separator).trim().lowercase()
                require(key.isNotBlank()) { "Bootstrap payload contains an empty field name" }
                require(put(key, line.substring(separator + 1).trim()) == null) {
                    "Bootstrap payload contains duplicate field '$key'"
                }
            }
        }
        return payloadFrom(entries, "Bootstrap payload")
    }

    private fun parseUri(value: String): CatBootstrapPayload {
        val uri = URI(value)
        require(
            uri.scheme.equals("cat", ignoreCase = true) &&
                uri.host.equals("pair", ignoreCase = true)
        ) {
            "Unsupported Cat bootstrap URI"
        }
        return payloadFrom(parseQuery(uri.rawQuery.orEmpty(), "Bootstrap URI"), "Bootstrap URI")
    }

    /**
     * The product-facing payload for QR codes, Android deep links and future server-panel buttons.
     * It remains a one-time bootstrap envelope and deliberately contains no device credential.
     */
    fun toDeepLink(payload: CatBootstrapPayload): String {
        val normalized = payload.normalized()
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        return "$PAIRING_PREFIX?server=${encode(normalized.server)}&fingerprint=${encode(normalized.certificateFingerprint)}&token=${encode(normalized.bootstrapToken)}"
    }

    /** Kept as a source-compatible alias; generated payloads now use the catpair:v1 scheme. */
    fun toUri(payload: CatBootstrapPayload): String {
        return toDeepLink(payload)
    }

    private fun parseDeepLink(value: String): CatBootstrapPayload {
        require(value.startsWith(PAIRING_PREFIX, ignoreCase = true)) {
            "Unsupported Cat pairing deep link"
        }
        val query = value.substring(PAIRING_PREFIX.length)
        require(query.startsWith('?')) { "Cat pairing deep link is missing fields" }
        return payloadFrom(
            parseQuery(query.drop(1), "Cat pairing deep link"),
            "Cat pairing deep link",
        )
    }

    private fun parseQuery(rawQuery: String, label: String): Map<String, String> {
        require(rawQuery.isNotBlank()) { "$label is missing fields" }
        return buildMap {
            rawQuery.split('&').filter(String::isNotBlank).forEach { item ->
                val separator = item.indexOf('=')
                require(separator > 0) { "$label fields must use key=value" }
                val key =
                    URLDecoder.decode(item.substring(0, separator), StandardCharsets.UTF_8)
                        .trim()
                        .lowercase()
                require(key.isNotBlank()) { "$label contains an empty field name" }
                require(
                    put(
                        key,
                        URLDecoder.decode(item.substring(separator + 1), StandardCharsets.UTF_8),
                    ) == null
                ) {
                    "$label contains duplicate field '$key'"
                }
            }
        }
    }

    private fun payloadFrom(entries: Map<String, String>, label: String): CatBootstrapPayload =
        CatBootstrapPayload(
            server = entries["server"] ?: entries["url"] ?: error("$label is missing server"),
            certificateFingerprint =
                entries["fingerprint"]
                    ?: entries["certificatefingerprint"]
                    ?: error("$label is missing fingerprint"),
            bootstrapToken =
                entries["token"] ?: entries["bootstraptoken"] ?: error("$label is missing token"),
        )
}

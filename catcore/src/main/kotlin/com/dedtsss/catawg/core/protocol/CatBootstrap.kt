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

    fun parse(raw: String): CatBootstrapPayload {
        val value = raw.trim()
        require(value.isNotBlank()) { "Bootstrap payload is empty" }
        val payload =
            when {
                value.startsWith("{") -> json.decodeFromString<CatBootstrapPayload>(value)
                value.startsWith("cat://", ignoreCase = true) -> parseUri(value)
                else -> parseText(value)
            }
        require(payload.bootstrapToken.isNotBlank()) {
            "Bootstrap payload is missing the one-time token"
        }
        return payload.normalized()
    }

    private fun parseText(value: String): CatBootstrapPayload {
        val lines = value.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(lines.firstOrNull()?.equals("catpair:v1", ignoreCase = true) == true) {
            "Unsupported bootstrap format; expected catpair:v1"
        }
        val entries =
            lines.drop(1).associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Bootstrap fields must use key=value lines" }
                line.substring(0, separator).trim().lowercase() to
                    line.substring(separator + 1).trim()
            }
        return CatBootstrapPayload(
            server =
                entries["server"] ?: entries["url"] ?: error("Bootstrap payload is missing server"),
            certificateFingerprint =
                entries["fingerprint"]
                    ?: entries["certificatefingerprint"]
                    ?: error("Bootstrap payload is missing fingerprint"),
            bootstrapToken =
                entries["token"]
                    ?: entries["bootstraptoken"]
                    ?: error("Bootstrap payload is missing token"),
        )
    }

    private fun parseUri(value: String): CatBootstrapPayload {
        val uri = URI(value)
        require(
            uri.scheme.equals("cat", ignoreCase = true) &&
                uri.host.equals("pair", ignoreCase = true)
        ) {
            "Unsupported Cat bootstrap URI"
        }
        val params =
            uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { item ->
                val separator = item.indexOf('=')
                require(separator > 0) { "Bootstrap URI fields must use key=value" }
                URLDecoder.decode(item.substring(0, separator), StandardCharsets.UTF_8) to
                    URLDecoder.decode(item.substring(separator + 1), StandardCharsets.UTF_8)
            }
        return CatBootstrapPayload(
            server = params["server"] ?: params["url"] ?: error("Bootstrap URI is missing server"),
            certificateFingerprint =
                params["fingerprint"] ?: error("Bootstrap URI is missing fingerprint"),
            bootstrapToken = params["token"] ?: error("Bootstrap URI is missing token"),
        )
    }

    fun toUri(payload: CatBootstrapPayload): String {
        val normalized = payload.normalized()
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        return "cat://pair?server=${encode(normalized.server)}&fingerprint=${encode(normalized.certificateFingerprint)}&token=${encode(normalized.bootstrapToken)}"
    }
}

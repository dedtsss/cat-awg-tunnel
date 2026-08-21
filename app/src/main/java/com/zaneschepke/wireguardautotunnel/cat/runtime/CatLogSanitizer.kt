package com.zaneschepke.wireguardautotunnel.cat.runtime

internal object CatLogSanitizer {
    private const val MAX_TEXT = 768
    private val control = Regex("[\\p{Cc}&&[^\\t\\n\\r]]")
    private val assignmentSecret = Regex(
        "(?i)(private[_ -]?key|preshared[_ -]?key|password|passwd|token|api[_ -]?key|secret|cookie|authorization|credential|bootstrap[_ -]?token)\\s*[:=]\\s*([^\\s,;]+)"
    )
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+\\-/]+=*")
    private val wgKeyLine = Regex("(?im)^\\s*(PrivateKey|PresharedKey)\\s*=\\s*[^\\r\\n]+$")
    private val sensitiveAttributeKeys = setOf(
        "private_key", "privatekey", "preshared_key", "presharedkey", "token", "password",
        "authorization", "credential", "secret", "bootstrap_token", "config", "raw_config"
    )

    fun text(value: String?): String? {
        if (value == null) return null
        var clean = value.replace(control, "").trim()
        clean = clean.replace(wgKeyLine) { "${it.groupValues[1]}=[redacted]" }
        clean = clean.replace(bearer, "Bearer [redacted]")
        clean = clean.replace(assignmentSecret) { "${it.groupValues[1]}=[redacted]" }
        if (clean.length > MAX_TEXT) clean = clean.take(MAX_TEXT)
        return clean.ifBlank { null }
    }

    fun throwable(error: Throwable?): String? {
        if (error == null) return null
        val type = error::class.java.simpleName.ifBlank { "Throwable" }
        return text(error.message)?.let { "$type: $it" } ?: type
    }

    fun attributes(values: Map<String, *>?): Map<String, String> {
        if (values.isNullOrEmpty()) return emptyMap()
        return buildMap {
            values.entries.take(24).forEach { (rawKey, rawValue) ->
                val key = rawKey.lowercase().trim()
                if (key in sensitiveAttributeKeys || rawValue == null) return@forEach
                text(rawValue.toString())?.let { put(key.take(64), it) }
            }
        }
    }
}

package com.dedtsss.catawg.core.configurator

/**
 * Small, deterministic INI parser for the portable contract; Android still uses upstream parsing to
 * apply.
 */
class AwgConfigParser {
    fun parse(raw: String): AwgConfigDocument {
        val interfaceValues = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var section: String? = null
        var currentPeer: MutableMap<String, String>? = null
        var hasInterface = false

        raw.lineSequence().forEachIndexed { index, originalLine ->
            val line = originalLine.substringBefore('#').substringBefore(';').trim()
            if (line.isBlank()) return@forEachIndexed
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                if (section == "interface" && hasInterface) {
                    throw AwgConfigParseException(
                        index + 1,
                        "Only one [Interface] section is allowed.",
                    )
                }
                if (section == "interface") hasInterface = true
                currentPeer =
                    if (section == "peer") linkedMapOf<String, String>().also { peers += it }
                    else null
                if (section != "interface" && section != "peer") {
                    throw AwgConfigParseException(index + 1, "Unsupported section [$section].")
                }
                return@forEachIndexed
            }
            val delimiter = line.indexOf('=')
            if (delimiter <= 0 || section == null) {
                throw AwgConfigParseException(
                    index + 1,
                    "Expected a key=value entry inside a section.",
                )
            }
            val key = canonicalKey(line.substring(0, delimiter).trim())
            val value = line.substring(delimiter + 1).trim()
            if (key.isBlank() || value.isBlank()) {
                throw AwgConfigParseException(
                    index + 1,
                    "Configuration keys and values cannot be empty.",
                )
            }
            when (section) {
                "interface" -> {
                    if (key in interfaceValues) {
                        throw AwgConfigParseException(
                            index + 1,
                            "Duplicate Interface parameter $key.",
                        )
                    }
                    interfaceValues[key] = value
                }
                "peer" -> {
                    val peer = requireNotNull(currentPeer)
                    if (key in peer) {
                        throw AwgConfigParseException(index + 1, "Duplicate Peer parameter $key.")
                    }
                    peer[key] = value
                }
            }
        }
        if (!hasInterface) throw AwgConfigParseException(1, "Missing [Interface] section.")
        return AwgConfigDocument(interfaceValues = interfaceValues, peers = peers)
    }

    fun serialize(document: AwgConfigDocument): String = buildString {
        appendLine("[Interface]")
        document.interfaceValues.forEach { (key, value) -> appendLine("$key = $value") }
        document.peers.forEach { peer ->
            appendLine()
            appendLine("[Peer]")
            peer.forEach { (key, value) -> appendLine("$key = $value") }
        }
    }

    private fun canonicalKey(value: String): String =
        value.trim().lowercase().replace("_", "").replace("-", "").let { normalized ->
            mapOf(
                "privatekey" to "PrivateKey",
                "publickey" to "PublicKey",
                "presharedkey" to "PresharedKey",
                "address" to "Address",
                "dns" to "DNS",
                "mtu" to "MTU",
                "allowedips" to "AllowedIPs",
                "persistentkeepalive" to "PersistentKeepalive",
                "listenport" to "ListenPort",
                "jmin" to "Jmin",
                "jmax" to "Jmax",
            )[normalized] ?: normalized.replaceFirstChar { it.uppercase() }
        }
}

class AwgConfigParseException(val line: Int, message: String) :
    IllegalArgumentException("Line $line: $message")

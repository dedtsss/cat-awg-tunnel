package com.dedtsss.catawg.core.configurator

/**
 * Validation uses the field ranges already exposed by upstream WG Tunnel's AWG2 editor. It does not
 * manufacture random obfuscation values and treats unknown AWG3 capability as unsupported.
 */
class AwgConfigValidator(
    private val parser: AwgConfigParser = AwgConfigParser(),
    private val compatibility: AwgCompatibilityEngine = AwgCompatibilityEngine(),
) {
    fun validate(
        raw: String,
        protocol: ConfigProtocol,
        capabilities: AwgCapabilities = AwgCapabilities(),
    ): ValidationResult {
        val document =
            try {
                parser.parse(raw)
            } catch (error: AwgConfigParseException) {
                return ValidationResult(
                    listOf(ValidationIssue("PARSE_ERROR", error.message ?: "Invalid config."))
                )
            }
        return validate(document, protocol, capabilities)
    }

    fun validate(
        document: AwgConfigDocument,
        protocol: ConfigProtocol,
        capabilities: AwgCapabilities = AwgCapabilities(),
    ): ValidationResult {
        val issues = compatibility.validate(protocol, capabilities).toMutableList()
        val interfaceValues = document.interfaceValues
        fun error(code: String, message: String, field: String) {
            issues += ValidationIssue(code, message, field)
        }
        fun warning(code: String, message: String, field: String) {
            issues += ValidationIssue(code, message, field, ValidationLevel.WARNING)
        }
        fun info(code: String, message: String, field: String) {
            issues += ValidationIssue(code, message, field, ValidationLevel.INFO)
        }

        interfaceValues.keys
            .filterNot { it in AwgConfigSchema.interfaceFields }
            .forEach { key ->
                warning(
                    "UNSUPPORTED_PARAMETER",
                    "$key is retained in the pasted document but is not supported by this AWG2 editor.",
                    "Interface.$key",
                )
            }
        document.peers.forEachIndexed { index, peer ->
            peer.keys
                .filterNot { it in AwgConfigSchema.peerFields }
                .forEach { key ->
                    warning(
                        "UNSUPPORTED_PARAMETER",
                        "$key is retained in the pasted document but is not supported by this AWG2 editor.",
                        "Peer[$index].$key",
                    )
                }
        }

        if (interfaceValues["PrivateKey"].isNullOrBlank())
            error("PRIVATE_KEY_REQUIRED", "PrivateKey is required.", "PrivateKey")
        if (interfaceValues["Address"].isNullOrBlank())
            error("ADDRESS_REQUIRED", "Address is required.", "Address")
        validateAddressList(interfaceValues["Address"], "Address", ::error)
        validateDns(interfaceValues["DNS"], ::error)
        validateUnsigned(interfaceValues["ListenPort"], "ListenPort", 1, 65535, ::error)
        validateUnsigned(interfaceValues["MTU"], "MTU", 1, 65535, ::error)
        interfaceValues["MTU"]?.toLongOrNull()?.let { mtu ->
            if (mtu in 1 until 576 || mtu > 9_000) {
                warning(
                    "UNUSUAL_MTU",
                    "MTU is outside the usual Internet range; verify it against the actual path rather than changing it automatically.",
                    "MTU",
                )
            }
        }

        if (document.peers.isEmpty())
            error("PEER_REQUIRED", "At least one [Peer] is required.", "Peer")
        document.peers.forEachIndexed { index, peer ->
            val prefix = "Peer[$index]"
            if (peer["PublicKey"].isNullOrBlank())
                error("PEER_PUBLIC_KEY_REQUIRED", "PublicKey is required.", "$prefix.PublicKey")
            if (peer["AllowedIPs"].isNullOrBlank())
                error("ALLOWED_IPS_REQUIRED", "AllowedIPs is required.", "$prefix.AllowedIPs")
            validateAllowedIps(peer["AllowedIPs"], "$prefix.AllowedIPs", ::error)
            if (!peer["Endpoint"].isNullOrBlank())
                validateEndpoint(peer["Endpoint"]!!, "$prefix.Endpoint", ::error)
            validateUnsigned(
                peer["PersistentKeepalive"],
                "$prefix.PersistentKeepalive",
                0,
                65535,
                ::error,
            )
        }

        val awgFields =
            setOf(
                "Jc",
                "Jmin",
                "Jmax",
                "S1",
                "S2",
                "S3",
                "S4",
                "H1",
                "H2",
                "H3",
                "H4",
                "I1",
                "I2",
                "I3",
                "I4",
                "I5",
            )
        val hasAwgValues = interfaceValues.keys.any { it in awgFields }
        if (hasAwgValues && protocol == ConfigProtocol.WIREGUARD) {
            error("AWG_FIELDS_IN_WG", "AWG parameters require an AWG2 profile.", "Interface")
        }
        if (hasAwgValues) {
            validateAwg2(interfaceValues, ::error, ::warning)
        } else if (protocol == ConfigProtocol.AWG2) {
            info(
                "AWG_DEFAULTS",
                "No AWG2 masking fields were supplied. The backend's documented zero/default semantics apply.",
                "Interface",
            )
        }
        return ValidationResult(issues.distinctBy { listOf(it.code, it.field, it.message) })
    }

    private fun validateAwg2(
        values: Map<String, String>,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        // AWG documents zero/default values as meaningful: an omitted parameter is treated as
        // zero.  Do not turn a valid but uncommon profile into an error by inventing a tuning
        // range.  These are type limits only; actual backend capability remains separately gated.
        validateUnsigned(values["Jc"], "Jc", 0, 65_535, error)
        validateUnsigned(values["Jmin"], "Jmin", 0, 65_535, error)
        validateUnsigned(values["Jmax"], "Jmax", 0, 65_535, error)
        validateUnsigned(values["S1"], "S1", 0, 65_535, error)
        validateUnsigned(values["S2"], "S2", 0, 65_535, error)
        validateUnsigned(values["S3"], "S3", 0, 65_535, error)
        validateUnsigned(values["S4"], "S4", 0, 65_535, error)
        listOf("H1", "H2", "H3", "H4").forEach { key ->
            validateHeaderRange(values[key], key, error)
        }
        val jMin = values["Jmin"]?.toLongOrNull()
        val jMax = values["Jmax"]?.toLongOrNull()
        val jCount = values["Jc"]?.toLongOrNull() ?: 0L
        if (jCount > 0 && (jMin == null || jMax == null)) {
            warning(
                "AWG_JUNK_BOUNDS_DEFAULT",
                "Jc is enabled while Jmin or Jmax relies on the backend default; verify the peer's expected behaviour.",
                "Jc",
            )
        }
        if (jCount > 0 && jMin != null && jMax != null && jMin > jMax) {
            error("AWG_JUNK_RANGE", "Jmin must not exceed Jmax.", "Jmin")
        }
        val hasMimic = (1..5).any { !values["I$it"].isNullOrBlank() }
        if (
            hasMimic &&
                listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4").any {
                    values[it].isNullOrBlank()
                }
        ) {
            warning(
                "AWG_BASE_PARAMETERS_MISSING",
                "Mimic packets are present but base AWG2 parameters are incomplete; upstream compatibility normalization may fill defaults.",
                "I1",
            )
        }
    }

    private fun validateHeaderRange(
        raw: String?,
        field: String,
        error: (String, String, String) -> Unit,
    ) {
        if (raw.isNullOrBlank()) return
        val bounds = raw.split('-').map(String::trim)
        if (bounds.size !in 1..2) {
            error(
                "INVALID_HEADER_RANGE",
                "$field must be a uint32 value or start-end range.",
                field,
            )
            return
        }
        val values = bounds.map { it.toLongOrNull() }
        if (values.any { value -> value == null || value < 0L || value > 4_294_967_295L }) {
            error("INVALID_HEADER_RANGE", "$field must use uint32 values or a uint32 range.", field)
            return
        }
        if (values.size == 2 && values[0]!! > values[1]!!) {
            error("INVALID_HEADER_RANGE", "$field range start must not exceed its end.", field)
        }
    }

    private fun validateUnsigned(
        raw: String?,
        field: String,
        minimum: Long,
        maximum: Long,
        error: (String, String, String) -> Unit,
    ) {
        if (raw.isNullOrBlank()) return
        val value = raw.toLongOrNull()
        if (value == null || value !in minimum..maximum) {
            error("INVALID_RANGE", "$field must be an integer from $minimum to $maximum.", field)
        }
    }

    private fun validateAddressList(
        raw: String?,
        field: String,
        error: (String, String, String) -> Unit,
    ) {
        if (raw.isNullOrBlank()) return
        raw.split(',').map(String::trim).filter(String::isNotBlank).forEach {
            validateCidr(it, field, error)
        }
    }

    private fun validateAllowedIps(
        raw: String?,
        field: String,
        error: (String, String, String) -> Unit,
    ) {
        if (raw.isNullOrBlank()) return
        raw.split(',').map(String::trim).filter(String::isNotBlank).forEach {
            validateCidr(it, field, error)
        }
    }

    private fun validateCidr(
        value: String,
        field: String,
        error: (String, String, String) -> Unit,
    ) {
        val parts = value.split('/')
        if (parts.size != 2 || parts[0].isBlank()) {
            error("INVALID_CIDR", "$value is not a CIDR address.", field)
            return
        }
        val prefix = parts[1].toIntOrNull()
        val max = if (':' in parts[0]) 128 else 32
        if (prefix == null || prefix !in 0..max)
            error("INVALID_CIDR", "$value has an invalid prefix length.", field)
    }

    private fun validateDns(raw: String?, error: (String, String, String) -> Unit) {
        if (raw.isNullOrBlank()) return
        if (raw.split(',').any { token -> token.trim().let { it.isBlank() || it.contains(' ') } }) {
            error(
                "INVALID_DNS",
                "DNS must be a comma-separated list of addresses or search domains.",
                "DNS",
            )
        }
    }

    private fun validateEndpoint(
        value: String,
        field: String,
        error: (String, String, String) -> Unit,
    ) {
        val port = value.substringAfterLast(':', "").toIntOrNull()
        if (port == null || port !in 1..65535 || !value.contains(':')) {
            error("INVALID_ENDPOINT", "Endpoint must include host:port with a valid port.", field)
        }
    }
}

package com.dedtsss.catawg.core.configurator

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class ConfigProtocol {
    WIREGUARD,
    AWG2,
    AWG3,
}

@Serializable
data class AwgCapabilities(
    val wireguard: Boolean = true,
    val awg2: Boolean = true,
    /** False by default: the bundled upstream backend is AWG2, not a claim of AWG3 support. */
    val awg3: Boolean = false,
    val serverWireguard: Boolean? = null,
    val serverAwg2: Boolean? = null,
    val serverAwg3: Boolean? = null,
)

@Serializable
data class AwgConfigDocument(
    val interfaceValues: Map<String, String> = emptyMap(),
    val peers: List<Map<String, String>> = emptyList(),
)

@Serializable
enum class ValidationLevel {
    ERROR,
    WARNING,
    INFO,
}

@Serializable
data class ValidationIssue(
    val code: String,
    val message: String,
    val field: String? = null,
    val level: ValidationLevel = ValidationLevel.ERROR,
)

@Serializable
data class ValidationResult(val issues: List<ValidationIssue> = emptyList()) {
    val isValid: Boolean
        get() = issues.none { it.level == ValidationLevel.ERROR }
}

/**
 * Protocol boundary for the in-app configurator. AWG3 deliberately has no implementation until the
 * bundled backend and the paired server advertise it; callers can therefore select a configurator
 * without treating a future protocol as supported today.
 */
interface ProtocolConfigurator {
    val protocol: ConfigProtocol

    fun parse(raw: String): AwgConfigDocument

    fun validate(
        document: AwgConfigDocument,
        capabilities: AwgCapabilities = AwgCapabilities(),
    ): ValidationResult
}

class Awg2ProtocolConfigurator(
    private val parser: AwgConfigParser = AwgConfigParser(),
    private val validator: AwgConfigValidator = AwgConfigValidator(parser),
) : ProtocolConfigurator {
    override val protocol: ConfigProtocol = ConfigProtocol.AWG2

    override fun parse(raw: String): AwgConfigDocument = parser.parse(raw)

    override fun validate(
        document: AwgConfigDocument,
        capabilities: AwgCapabilities,
    ): ValidationResult = validator.validate(document, protocol, capabilities)
}

/** Versioned schema metadata for clients that want to build a UI without inventing parameters. */
object AwgConfigSchema {
    const val VERSION = "cat.awg-config.v1"

    val interfaceFields =
        setOf(
            "PrivateKey",
            "Address",
            "DNS",
            "ListenPort",
            "MTU",
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
    val peerFields =
        setOf("PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive")
}

@Serializable
data class ConfigProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: ConfigProtocol,
    val document: AwgConfigDocument,
    val capabilityRequirements: Set<String> = emptySet(),
    val validation: ValidationResult = ValidationResult(),
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
)

@Serializable
data class PublicConfigProfile(
    val id: String,
    val name: String,
    val protocol: ConfigProtocol,
    val parameters: Map<String, String>,
    val capabilityRequirements: Set<String>,
    val validationSummary: PublicValidationSummary,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PublicValidationSummary(val valid: Boolean, val issueCodes: List<String> = emptyList())

fun ConfigProfile.toPublic(): PublicConfigProfile {
    val publicParameters =
        document.interfaceValues.filterKeys(::isPublicConfigKey) +
            document.peers
                .flatMapIndexed { index, peer ->
                    peer.filterKeys(::isPublicConfigKey).map { (key, value) ->
                        "peer[$index].$key" to value
                    }
                }
                .toMap()
    return PublicConfigProfile(
        id = id,
        name = name,
        protocol = protocol,
        parameters = publicParameters,
        capabilityRequirements = capabilityRequirements,
        validationSummary =
            PublicValidationSummary(
                valid = validation.isValid,
                issueCodes = validation.issues.map { it.code },
            ),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun isPublicConfigKey(key: String): Boolean {
    val normalized = key.lowercase().replace(Regex("[^a-z0-9]"), "")
    return normalized !in
        setOf(
            "privatekey",
            "presharedkey",
            "password",
            "token",
            "secret",
            "apikey",
            "authorization",
        )
}

data class AwgParameterMetadata(
    val key: String,
    val title: String,
    val description: String,
    val validRange: String? = null,
)

/** Bundled, deterministic explanations used when the optional server/AI assistant is offline. */
object AwgParameterMetadataCatalog {
    val all =
        listOf(
            AwgParameterMetadata(
                "Address",
                "Interface address",
                "The tunnel addresses assigned to this device.",
            ),
            AwgParameterMetadata("DNS", "DNS", "Resolvers used while the tunnel is active."),
            AwgParameterMetadata(
                "MTU",
                "MTU",
                "Maximum transmission unit for the tunnel interface.",
                "1..65535",
            ),
            AwgParameterMetadata(
                "ListenPort",
                "Listen port",
                "Local UDP port used by the interface.",
                "1..65535",
            ),
            AwgParameterMetadata(
                "AllowedIPs",
                "Allowed IPs",
                "Peer routes; 0.0.0.0/0 and ::/0 represent full-tunnel routing.",
            ),
            AwgParameterMetadata(
                "Endpoint",
                "Endpoint",
                "The server hostname or address and UDP port.",
            ),
            AwgParameterMetadata(
                "PersistentKeepalive",
                "Persistent keepalive",
                "Optional interval for NAT mapping maintenance.",
                "0..65535",
            ),
            AwgParameterMetadata(
                "Jc",
                "Junk packet count",
                "AWG2 obfuscation packet count.",
                "0..65535",
            ),
            AwgParameterMetadata(
                "Jmin",
                "Junk minimum",
                "Minimum AWG2 junk packet size.",
                "0..65535",
            ),
            AwgParameterMetadata(
                "Jmax",
                "Junk maximum",
                "Maximum AWG2 junk packet size; must be at least Jmin.",
                "0..65535; Jmin ≤ Jmax when Jc is enabled",
            ),
            AwgParameterMetadata(
                "S1",
                "Init padding",
                "AWG2 handshake padding parameter.",
                "0..65535",
            ),
            AwgParameterMetadata(
                "S2",
                "Response padding",
                "AWG2 response padding parameter.",
                "0..65535",
            ),
            AwgParameterMetadata(
                "S3",
                "Cookie padding",
                "AWG2 cookie padding parameter.",
                "0..65535",
            ),
            AwgParameterMetadata(
                "S4",
                "Transport padding",
                "AWG2 transport padding parameter.",
                "0..65535",
            ),
            AwgParameterMetadata(
                "H1",
                "Handshake header 1",
                "AWG2 uint32 header value or inclusive uint32 range.",
                "0..4294967295 or start-end",
            ),
            AwgParameterMetadata(
                "H2",
                "Handshake header 2",
                "AWG2 uint32 header value or inclusive uint32 range.",
                "0..4294967295 or start-end",
            ),
            AwgParameterMetadata(
                "H3",
                "Handshake header 3",
                "AWG2 uint32 header value or inclusive uint32 range.",
                "0..4294967295 or start-end",
            ),
            AwgParameterMetadata(
                "H4",
                "Handshake header 4",
                "AWG2 uint32 header value or inclusive uint32 range.",
                "0..4294967295 or start-end",
            ),
        )

    fun find(key: String): AwgParameterMetadata? = all.firstOrNull {
        it.key.equals(key, ignoreCase = true)
    }
}

@Serializable
data class ConfigurationChange(
    val id: String = UUID.randomUUID().toString(),
    val at: String = Instant.now().toString(),
    val priorProfileId: String? = null,
    val newProfileId: String,
    val reason: String,
    val recommendationSource: String? = null,
    val diagnosticAction: DiagnosticAction? = null,
    val result: ConfigurationResult? = null,
)

@Serializable
data class DiagnosticAction(val id: String = UUID.randomUUID().toString(), val description: String)

@Serializable
data class ConfigurationResult(
    val applied: Boolean,
    val incidentDelta: Int? = null,
    val reconnectDelta: Int? = null,
    val rttDeltaMs: Long? = null,
    val lossDeltaPercent: Double? = null,
    val note: String? = null,
)

@Serializable
data class Recommendation(
    val id: String = UUID.randomUUID().toString(),
    val summary: String,
    val rationale: String,
    val candidateProfileId: String? = null,
    val requiresExplicitApply: Boolean = true,
)

interface AwgProfileRepository {
    suspend fun save(profile: ConfigProfile)

    suspend fun get(id: String): ConfigProfile?

    suspend fun changes(profileId: String? = null): List<ConfigurationChange>

    suspend fun recordChange(change: ConfigurationChange)
}

interface AwgRecommendationEngine {
    suspend fun recommend(
        profile: ConfigProfile,
        context: Map<String, String> = emptyMap(),
    ): List<Recommendation>
}

interface AwgTestEngine {
    suspend fun test(candidate: ConfigProfile): ConfigurationResult
}

/**
 * Local deterministic repository for tests and offline UI prototyping; production storage is
 * injectable.
 */
class InMemoryAwgProfileRepository : AwgProfileRepository {
    private val profiles = linkedMapOf<String, ConfigProfile>()
    private val changeEntries = mutableListOf<ConfigurationChange>()

    override suspend fun save(profile: ConfigProfile) {
        profiles[profile.id] = profile
    }

    override suspend fun get(id: String): ConfigProfile? = profiles[id]

    override suspend fun changes(profileId: String?): List<ConfigurationChange> =
        changeEntries.filter {
            profileId == null || it.newProfileId == profileId || it.priorProfileId == profileId
        }

    override suspend fun recordChange(change: ConfigurationChange) {
        changeEntries.removeAll { it.id == change.id }
        changeEntries += change
    }
}

class AwgCompatibilityEngine {
    fun validate(protocol: ConfigProtocol, capabilities: AwgCapabilities): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        when (protocol) {
            ConfigProtocol.WIREGUARD -> {
                if (!capabilities.wireguard) {
                    issues +=
                        ValidationIssue(
                            "CLIENT_WG_UNSUPPORTED",
                            "WireGuard is unavailable in this client.",
                        )
                }
                if (capabilities.serverWireguard == false) {
                    issues +=
                        ValidationIssue(
                            "SERVER_WG_UNSUPPORTED",
                            "The paired server does not advertise WireGuard.",
                        )
                }
            }

            ConfigProtocol.AWG2 -> {
                if (!capabilities.awg2) {
                    issues +=
                        ValidationIssue(
                            "CLIENT_AWG2_UNSUPPORTED",
                            "AmneziaWG 2 is unavailable in this client.",
                        )
                }
                if (capabilities.serverAwg2 == false) {
                    issues +=
                        ValidationIssue(
                            "SERVER_AWG2_UNSUPPORTED",
                            "The paired server does not advertise AmneziaWG 2.",
                        )
                }
            }

            ConfigProtocol.AWG3 -> {
                if (!capabilities.awg3) {
                    issues +=
                        ValidationIssue(
                            "CLIENT_AWG3_UNSUPPORTED",
                            "AWG3 is capability-gated and is not supported by this bundled backend.",
                        )
                }
                if (capabilities.serverAwg3 == false) {
                    issues +=
                        ValidationIssue(
                            "SERVER_AWG3_UNSUPPORTED",
                            "The paired server does not advertise AWG3.",
                        )
                }
            }
        }
        return issues
    }
}

class AwgConfigGenerator(private val validator: AwgConfigValidator = AwgConfigValidator()) {
    /** Produces a candidate only. Applying a candidate always remains an explicit caller action. */
    fun candidate(
        name: String,
        protocol: ConfigProtocol,
        document: AwgConfigDocument,
        capabilities: AwgCapabilities = AwgCapabilities(),
    ): ConfigProfile {
        val validation = validator.validate(document, protocol, capabilities)
        return ConfigProfile(
            name = name,
            protocol = protocol,
            document = document,
            capabilityRequirements =
                when (protocol) {
                    ConfigProtocol.WIREGUARD -> setOf("wireguard")
                    ConfigProtocol.AWG2 -> setOf("awg2")
                    ConfigProtocol.AWG3 -> setOf("awg3")
                },
            validation = validation,
        )
    }
}

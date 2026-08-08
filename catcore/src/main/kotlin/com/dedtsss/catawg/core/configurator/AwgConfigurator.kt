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
    val peerFields = setOf("PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive")
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
    val validation: ValidationResult,
    val createdAt: String,
    val updatedAt: String,
)

fun ConfigProfile.toPublic(): PublicConfigProfile {
    val publicParameters =
        document.interfaceValues.filterKeys(::isPublicConfigKey) +
            document.peers.flatMapIndexed { index, peer ->
                peer.filterKeys(::isPublicConfigKey).map { (key, value) -> "peer[$index].$key" to value }
            }.toMap()
    return PublicConfigProfile(
        id = id,
        name = name,
        protocol = protocol,
        parameters = publicParameters,
        capabilityRequirements = capabilityRequirements,
        validation = validation,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun isPublicConfigKey(key: String): Boolean {
    val normalized = key.lowercase().replace(Regex("[^a-z0-9]"), "")
    return normalized !in setOf("privatekey", "presharedkey", "password", "token", "secret", "apikey")
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
    suspend fun recommend(profile: ConfigProfile, context: Map<String, String> = emptyMap()): List<Recommendation>
}

interface AwgTestEngine {
    suspend fun test(candidate: ConfigProfile): ConfigurationResult
}

/** Local deterministic repository for tests and offline UI prototyping; production storage is injectable. */
class InMemoryAwgProfileRepository : AwgProfileRepository {
    private val profiles = linkedMapOf<String, ConfigProfile>()
    private val changeEntries = mutableListOf<ConfigurationChange>()

    override suspend fun save(profile: ConfigProfile) {
        profiles[profile.id] = profile
    }

    override suspend fun get(id: String): ConfigProfile? = profiles[id]

    override suspend fun changes(profileId: String?): List<ConfigurationChange> =
        changeEntries.filter { profileId == null || it.newProfileId == profileId || it.priorProfileId == profileId }

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
                    issues += ValidationIssue("CLIENT_WG_UNSUPPORTED", "WireGuard is unavailable in this client.")
                }
                if (capabilities.serverWireguard == false) {
                    issues += ValidationIssue("SERVER_WG_UNSUPPORTED", "The paired server does not advertise WireGuard.")
                }
            }

            ConfigProtocol.AWG2 -> {
                if (!capabilities.awg2) {
                    issues += ValidationIssue("CLIENT_AWG2_UNSUPPORTED", "AmneziaWG 2 is unavailable in this client.")
                }
                if (capabilities.serverAwg2 == false) {
                    issues += ValidationIssue("SERVER_AWG2_UNSUPPORTED", "The paired server does not advertise AmneziaWG 2.")
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
                    issues += ValidationIssue("SERVER_AWG3_UNSUPPORTED", "The paired server does not advertise AWG3.")
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

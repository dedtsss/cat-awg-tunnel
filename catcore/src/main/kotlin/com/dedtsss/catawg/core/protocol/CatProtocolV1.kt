package com.dedtsss.catawg.core.protocol

import com.dedtsss.catawg.core.configurator.PublicConfigProfile
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import com.dedtsss.catawg.core.diagnostics.Incident
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Cat Protocol v1. JSON is camelCase and all timestamps are RFC3339 with a UTC offset. */
object CatProtocolV1 {
    const val SCHEMA_VERSION = "cat.v1"
    const val API_PREFIX = "/api/v1"
    const val HEALTH = "$API_PREFIX/health"
    const val CAPABILITIES = "$API_PREFIX/capabilities"
    const val PAIRING_START = "$API_PREFIX/pairing/start"
    const val PAIRING_COMPLETE = "$API_PREFIX/pairing/complete"
    const val DIAGNOSTIC_EVENTS = "$API_PREFIX/diagnostics/events"
    const val INCIDENTS = "$API_PREFIX/incidents"
    const val DIAGNOSTIC_BUNDLE = "$API_PREFIX/diagnostics/bundle"
    const val CONFIG_VALIDATE = "$API_PREFIX/config/validate"
    const val METRICS_COMPARE = "$API_PREFIX/metrics/compare"
    const val AI_CHAT = "$API_PREFIX/ai/chat"
}

private val CatJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
}

@Serializable data class EngineCapability(val supported: Boolean, val version: String? = null)

/** Future server capability additions are deliberately ignored by the Android JSON parser. */
@Serializable
data class ServerFeatures(
    val diagnostics: Boolean = false,
    val configManagement: Boolean = false,
    val serverRouting: Boolean = false,
    val routingBackend: String? = null,
    val aiGateway: Boolean = false,
)

@Serializable
data class ServerCapabilities(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val serverId: String,
    val agentVersion: String,
    val os: String,
    val architecture: String,
    val engines: Map<String, EngineCapability>,
    val features: ServerFeatures,
    val managementApiVersion: String,
)

@Serializable
data class CatHealth(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val status: String,
    val agentVersion: String,
    val at: String = Instant.now().toString(),
)

@Serializable
data class ConfigValidationRequest(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val publicProfile: PublicConfigProfile,
)

@Serializable
data class ConfigValidationIssue(
    val code: String,
    val field: String? = null,
    val message: String,
    val severity: String = "ERROR",
)

@Serializable
data class ConfigValidationResponse(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val valid: Boolean,
    val issues: List<ConfigValidationIssue> = emptyList(),
    val publicProfile: PublicConfigProfile,
    val capabilitySatisfied: Boolean,
)

@Serializable data class TimeRange(val from: String, val to: String)

@Serializable
data class ReliabilityMetrics(
    val incidents: Int = 0,
    val downtimeSeconds: Double = 0.0,
    val reconnectEvents: Int = 0,
    val tunnelServiceRestarts: Int = 0,
    val rttAverageMs: Double? = null,
    val packetLossAveragePercent: Double? = null,
    val sampleNote: String = "",
)

@Serializable
data class MetricsCompareResponse(
    val changeAt: String,
    val before: ReliabilityMetrics = ReliabilityMetrics(),
    val after: ReliabilityMetrics = ReliabilityMetrics(),
    val windowSeconds: Long = 0,
)

@Serializable
data class DiagnosticUploadRequest(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val events: List<DiagnosticEvent>,
)

/** Server-enriched events contain authenticated deviceId; Android never sends one. */
@Serializable
data class DiagnosticUploadResponse(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val events: List<DiagnosticEvent>,
)

@Serializable
data class IncidentsResponse(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val incidents: List<Incident> = emptyList(),
)

@Serializable
data class PairingStartResponse(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val pairingId: String,
    val code: String,
    val expiresAt: String,
    val certificateFingerprint: String,
)

@Serializable
data class PairingCompleteRequest(val pairingId: String, val code: String, val deviceName: String)

@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val createdAt: String,
    val lastAccessedAt: String? = null,
    val revokedAt: String? = null,
)

@Serializable
data class PairingCompleteResponse(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val device: PairedDevice,
    val deviceToken: String,
    val certificateFingerprint: String,
) {
    override fun toString(): String =
        "PairingCompleteResponse(schemaVersion=$schemaVersion, device=$device, deviceToken=[REDACTED], certificateFingerprint=$certificateFingerprint)"
}

@Serializable
data class CatAiChatRequest(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val message: String,
    val incidentId: String? = null,
    val model: String? = null,
    val memoryEnabled: Boolean = false,
)

@Serializable
data class CatAiChatResponse(
    val schemaVersion: String = CatProtocolV1.SCHEMA_VERSION,
    val message: String,
    val recommendations: List<String> = emptyList(),
    val candidateOnly: Boolean = true,
    val applied: Boolean = false,
)

/** Bootstrap material must be obtained out of band; it is not persisted by this client. */
data class PairingBootstrap(val certificateFingerprint: String, val bootstrapToken: String) {
    override fun toString(): String =
        "PairingBootstrap(certificateFingerprint=$certificateFingerprint, bootstrapToken=[REDACTED])"
}

/**
 * A non-secret boundary marker for a failed Cat pairing operation.
 *
 * The underlying cause is retained for local diagnostics, but the stage itself contains no
 * bootstrap material, device token, response body, or cryptographic key data and is safe to show in
 * the Android UI.
 */
enum class CatServerOperationStage {
    HEALTH,
    PAIRING_START,
    PAIRING_COMPLETE,
    CREDENTIAL_PERSISTENCE,
    PAIRING_SETTINGS,
    CAPABILITIES,
}

class CatServerOperationException(val stage: CatServerOperationStage, cause: Throwable) :
    RuntimeException("Cat Server operation failed at ${stage.name}", cause)

/**
 * Implement using Android Keystore-backed encrypted storage. No plaintext default implementation
 * exists.
 */
interface CatServerCredentialStore {
    fun read(): CatServerCredentials?

    fun write(credentials: CatServerCredentials)

    fun clear()
}

data class CatServerCredentials(
    val deviceToken: String,
    val certificateFingerprint: String,
    val deviceId: String,
) {
    override fun toString(): String =
        "CatServerCredentials(deviceToken=[REDACTED], certificateFingerprint=$certificateFingerprint, deviceId=$deviceId)"
}

interface CatServerClient {
    suspend fun health(): CatHealth

    suspend fun pair(deviceName: String, bootstrap: PairingBootstrap): PairingCompleteResponse

    suspend fun capabilities(): ServerCapabilities

    suspend fun postDiagnosticEvents(events: List<DiagnosticEvent>): List<DiagnosticEvent>

    suspend fun incidents(range: TimeRange): IncidentsResponse

    suspend fun diagnosticBundle(range: TimeRange): ByteArray

    suspend fun validateConfig(request: ConfigValidationRequest): ConfigValidationResponse

    suspend fun metricsCompare(changeAt: String, windowHours: Int = 24): MetricsCompareResponse

    suspend fun aiChat(request: CatAiChatRequest): CatAiChatResponse?

    fun revokeLocalCredentials()
}

/**
 * Real Ktor client. TLS is never relaxed: a supplied SHA-256 DER-certificate fingerprint is checked
 * for every handshake and the platform hostname verifier remains enabled.
 */
class KtorCatServerClient(
    private val baseUrl: String,
    private val credentials: CatServerCredentialStore,
    private val initialCertificateFingerprint: String? = null,
) : CatServerClient, AutoCloseable {
    init {
        require(normalizeCatServerUrl(baseUrl) == baseUrl) {
            "Cat Server requires a normalized HTTPS URL"
        }
    }

    private fun pinnedClient(fingerprint: String): HttpClient {
        val trustManager = CertificateFingerprintTrustManager(fingerprint)
        val sslContext =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) { json(CatJson) }
            engine { config { sslSocketFactory(sslContext.socketFactory, trustManager) } }
        }
    }

    private fun currentCredentials(): CatServerCredentials =
        requireNotNull(credentials.read()) {
            "Pair with Cat Server before making authenticated requests"
        }

    private fun currentClient(): HttpClient =
        pinnedClient(currentCredentials().certificateFingerprint)

    private fun bootstrapClient(bootstrap: PairingBootstrap): HttpClient =
        pinnedClient(bootstrap.certificateFingerprint)

    override suspend fun health(): CatHealth {
        val fingerprint =
            credentials.read()?.certificateFingerprint
                ?: initialCertificateFingerprint
                ?: error("A verified server certificate fingerprint is required before connecting")
        return operation(CatServerOperationStage.HEALTH) {
            pinnedClient(fingerprint).use { it.get(baseUrl + CatProtocolV1.HEALTH).body() }
        }
    }

    override suspend fun pair(
        deviceName: String,
        bootstrap: PairingBootstrap,
    ): PairingCompleteResponse =
        bootstrap
            .copy(
                certificateFingerprint =
                    normalizeCertificateFingerprint(bootstrap.certificateFingerprint)
            )
            .let { normalized ->
                bootstrapClient(normalized).use { client ->
                    val start: PairingStartResponse =
                        operation(CatServerOperationStage.PAIRING_START) {
                            client
                                .post(baseUrl + CatProtocolV1.PAIRING_START) {
                                    header("X-Cat-Bootstrap-Token", normalized.bootstrapToken)
                                }
                                .body()
                        }
                    require(
                        sameFingerprint(
                            start.certificateFingerprint,
                            normalized.certificateFingerprint,
                        )
                    ) {
                        "Pairing response certificate identity differs from verified bootstrap material"
                    }
                    val completed: PairingCompleteResponse =
                        operation(CatServerOperationStage.PAIRING_COMPLETE) {
                            client
                                .post(baseUrl + CatProtocolV1.PAIRING_COMPLETE) {
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        PairingCompleteRequest(
                                            start.pairingId,
                                            start.code,
                                            deviceName,
                                        )
                                    )
                                }
                                .body()
                        }
                    require(
                        sameFingerprint(
                            completed.certificateFingerprint,
                            normalized.certificateFingerprint,
                        )
                    ) {
                        "Pairing completion certificate identity differs from verified bootstrap material"
                    }
                    operation(CatServerOperationStage.CREDENTIAL_PERSISTENCE) {
                        credentials.write(
                            CatServerCredentials(
                                completed.deviceToken,
                                completed.certificateFingerprint,
                                completed.device.id,
                            )
                        )
                    }
                    completed
                }
            }

    override suspend fun capabilities(): ServerCapabilities =
        operation(CatServerOperationStage.CAPABILITIES) {
            authenticated { client, token ->
                client
                    .get(baseUrl + CatProtocolV1.CAPABILITIES) {
                        header("Authorization", "Bearer $token")
                    }
                    .body()
            }
        }

    private suspend inline fun <T> operation(
        stage: CatServerOperationStage,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: CatServerOperationException) {
            throw error
        } catch (error: Throwable) {
            throw CatServerOperationException(stage, error)
        }

    override suspend fun postDiagnosticEvents(
        events: List<DiagnosticEvent>
    ): List<DiagnosticEvent> = authenticated { client, token ->
        require(events.isNotEmpty()) { "At least one diagnostic event is required" }
        require(events.size <= 200) { "At most 200 diagnostic events may be uploaded in one batch" }
        require(events.all { it.source.name == "CLIENT" }) {
            "Android may upload CLIENT events only"
        }
        client
            .post(baseUrl + CatProtocolV1.DIAGNOSTIC_EVENTS) {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(DiagnosticUploadRequest(events = events))
            }
            .body<DiagnosticUploadResponse>()
            .events
    }

    override suspend fun incidents(range: TimeRange): IncidentsResponse =
        authenticated { client, token ->
            client
                .get(baseUrl + CatProtocolV1.INCIDENTS) {
                    header("Authorization", "Bearer $token")
                    url {
                        parameters.append("from", range.from)
                        parameters.append("to", range.to)
                    }
                }
                .body()
        }

    override suspend fun diagnosticBundle(range: TimeRange): ByteArray =
        authenticated { client, token ->
            client
                .get(baseUrl + CatProtocolV1.DIAGNOSTIC_BUNDLE) {
                    header("Authorization", "Bearer $token")
                    url {
                        parameters.append("from", range.from)
                        parameters.append("to", range.to)
                    }
                }
                .bodyAsBytes()
        }

    override suspend fun validateConfig(
        request: ConfigValidationRequest
    ): ConfigValidationResponse = authenticated { client, token ->
        require(request.publicProfile.parameters.keys.none(::isSecretBearingConfigKey)) {
            "Generic Cat config validation accepts only a public, non-secret profile"
        }
        client
            .post(baseUrl + CatProtocolV1.CONFIG_VALIDATE) {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()
    }

    override suspend fun metricsCompare(
        changeAt: String,
        windowHours: Int,
    ): MetricsCompareResponse = authenticated { client, token ->
        require(windowHours in 1..168) {
            "Metrics comparison window must be between 1 and 168 hours"
        }
        client
            .get(baseUrl + CatProtocolV1.METRICS_COMPARE) {
                header("Authorization", "Bearer $token")
                url {
                    parameters.append("changeAt", changeAt)
                    parameters.append("windowHours", windowHours.toString())
                }
            }
            .body()
    }

    override suspend fun aiChat(request: CatAiChatRequest): CatAiChatResponse? =
        authenticated { client, token ->
            client
                .post(baseUrl + CatProtocolV1.AI_CHAT) {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()
        }

    private suspend fun <T> authenticated(block: suspend (HttpClient, String) -> T): T {
        val stored = currentCredentials()
        return currentClient().use { block(it, stored.deviceToken) }
    }

    override fun revokeLocalCredentials() = credentials.clear()

    override fun close() = Unit
}

private fun sameFingerprint(left: String, right: String): Boolean =
    runCatching { normalizeCertificateFingerprint(left) == normalizeCertificateFingerprint(right) }
        .getOrDefault(false)

fun isSecretBearingConfigKey(key: String): Boolean {
    val normalized = key.lowercase().filter(Char::isLetterOrDigit)
    return listOf(
            "privatekey",
            "presharedkey",
            "password",
            "token",
            "secret",
            "apikey",
            "authorization",
        )
        .any(normalized::contains)
}

/** Canonical form used for comparison, persistence and TLS pinning. */
fun normalizeCertificateFingerprint(value: String): String {
    val withoutPrefix = value.trim().replace(Regex("(?i)^sha[- ]?256\\s*:\\s*"), "")
    val compact = withoutPrefix.filterNot { it == ':' || it == '-' || it.isWhitespace() }
    require(compact.length == 64 && compact.all { it in "0123456789abcdefABCDEF" }) {
        "Certificate fingerprint must be a SHA-256 value with 64 hexadecimal characters"
    }
    return "sha256:${compact.lowercase()}"
}

fun displayCertificateFingerprint(value: String): String =
    normalizeCertificateFingerprint(value).removePrefix("sha256:").chunked(2).joinToString(":") {
        it.uppercase()
    }

/** Management URLs are deliberately restricted to HTTPS origins without hidden path/query state. */
fun normalizeCatServerUrl(value: String): String {
    val candidate = value.trim().removeSuffix("/")
    val uri =
        runCatching { URI(candidate) }
            .getOrElse { throw IllegalArgumentException("Cat Server URL is invalid") }
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "Cat Server requires HTTPS; HTTP management is disabled"
    }
    require(!uri.host.isNullOrBlank() && uri.userInfo == null) {
        "Cat Server URL must contain a hostname without credentials"
    }
    require(uri.path.isNullOrBlank() || uri.path == "/") {
        "Cat Server URL must be the server origin"
    }
    require(uri.query == null && uri.fragment == null) {
        "Cat Server URL must not contain query or fragment data"
    }
    return "https://${uri.rawAuthority}"
}

private class CertificateFingerprintTrustManager(expectedFingerprint: String) : X509TrustManager {
    private val expected =
        normalizeCertificateFingerprint(expectedFingerprint).removePrefix("sha256:")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val certificate =
            chain.firstOrNull()
                ?: throw CertificateException("Server did not provide a certificate")
        // Pinning identifies the expected leaf, while validity dates remain part of verified TLS.
        certificate.checkValidity()
        val actual =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {
                "%02x".format(it)
            }
        if (actual != expected)
            throw CertificateException("Cat Server certificate fingerprint mismatch")
    }
}

/** Test-only boundary; production DI must use [KtorCatServerClient] with encrypted credentials. */
class InMemoryCatServerClient(
    private val advertisedCapabilities: ServerCapabilities =
        ServerCapabilities(
            serverId = "local-mock",
            agentVersion = "0",
            os = "mock",
            architecture = "mock",
            engines =
                mapOf(
                    "wireguard" to EngineCapability(true),
                    "awg2" to EngineCapability(true),
                    "awg3" to EngineCapability(false),
                ),
            features = ServerFeatures(diagnostics = true),
            managementApiVersion = "v1",
        )
) : CatServerClient {
    private val recordedEvents = mutableListOf<DiagnosticEvent>()

    override suspend fun health() = CatHealth(status = "ok", agentVersion = "0")

    override suspend fun pair(
        deviceName: String,
        bootstrap: PairingBootstrap,
    ): PairingCompleteResponse =
        PairingCompleteResponse(
            device = PairedDevice("mock-device", deviceName, Instant.now().toString()),
            deviceToken = "mock",
            certificateFingerprint = bootstrap.certificateFingerprint,
        )

    override suspend fun capabilities() = advertisedCapabilities

    override suspend fun postDiagnosticEvents(
        events: List<DiagnosticEvent>
    ): List<DiagnosticEvent> = events.also { recordedEvents += it }

    override suspend fun incidents(range: TimeRange) = IncidentsResponse()

    override suspend fun diagnosticBundle(range: TimeRange) = ByteArray(0)

    override suspend fun validateConfig(request: ConfigValidationRequest) =
        ConfigValidationResponse(
            valid = request.publicProfile.validationSummary.valid,
            publicProfile = request.publicProfile,
            capabilitySatisfied = true,
        )

    override suspend fun metricsCompare(changeAt: String, windowHours: Int) =
        MetricsCompareResponse(changeAt)

    override suspend fun aiChat(request: CatAiChatRequest): CatAiChatResponse? = null

    override fun revokeLocalCredentials() = Unit
}

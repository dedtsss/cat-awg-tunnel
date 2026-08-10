package com.zaneschepke.wireguardautotunnel.cat.server

import com.dedtsss.catawg.core.ai.AiAssistantRequest
import com.dedtsss.catawg.core.ai.AiAssistantResponse
import com.dedtsss.catawg.core.ai.CatAiProvider
import com.dedtsss.catawg.core.configurator.Recommendation
import com.dedtsss.catawg.core.protocol.CatAiChatRequest
import com.dedtsss.catawg.core.protocol.CatHealth
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.CatServerOperationException
import com.dedtsss.catawg.core.protocol.CatServerOperationStage
import com.dedtsss.catawg.core.protocol.ConfigValidationRequest
import com.dedtsss.catawg.core.protocol.ConfigValidationResponse
import com.dedtsss.catawg.core.protocol.IncidentsResponse
import com.dedtsss.catawg.core.protocol.KtorCatServerClient
import com.dedtsss.catawg.core.protocol.MetricsCompareResponse
import com.dedtsss.catawg.core.protocol.PairingBootstrap
import com.dedtsss.catawg.core.protocol.PairingCompleteResponse
import com.dedtsss.catawg.core.protocol.ServerCapabilities
import com.dedtsss.catawg.core.protocol.TimeRange
import com.dedtsss.catawg.core.protocol.normalizeCertificateFingerprint
import com.zaneschepke.wireguardautotunnel.data.cat.CatCredentialPersistenceFailure
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import io.ktor.client.plugins.ResponseException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production Android boundary. It resolves the current endpoint from non-secret settings and
 * delegates every network call to the real pinned Ktor client. The in-memory client is not used by
 * application DI.
 */
class AndroidCatServerClient(
    private val settingsStore: CatServerSettingsStore,
    private val credentials: CatServerCredentialStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
) : CatServerClient {
    private suspend fun configuredClient(): KtorCatServerClient {
        val settings = settingsStore.read()
        val serverUrl = requireNotNull(settings.serverUrl) { "Cat Server is not configured" }
        return KtorCatServerClient(serverUrl, credentials, settings.certificateFingerprint)
    }

    private suspend fun <T> call(block: suspend (KtorCatServerClient) -> T): T =
        withContext(ioDispatcher) {
            runCatching { configuredClient().use { client -> block(client) } }
                .onSuccess { settingsStore.recordContact() }
                .onFailure { error -> settingsStore.recordError(CatServerErrorMapper.code(error)) }
                .getOrThrow()
        }

    override suspend fun health(): CatHealth = call { it.health() }

    override suspend fun pair(
        deviceName: String,
        bootstrap: PairingBootstrap,
    ): PairingCompleteResponse {
        require(deviceName.isNotBlank()) { "Device name is required" }
        val normalizedBootstrap =
            bootstrap.copy(
                certificateFingerprint =
                    normalizeCertificateFingerprint(bootstrap.certificateFingerprint)
            )
        val result = call { it.pair(deviceName.trim(), normalizedBootstrap) }
        try {
            settingsStore.markPaired(
                result.device.id,
                result.device.name,
                result.certificateFingerprint,
            )
        } catch (error: Throwable) {
            val staged = CatServerOperationException(CatServerOperationStage.PAIRING_SETTINGS, error)
            settingsStore.recordError(CatServerErrorMapper.code(staged))
            throw staged
        }
        return result
    }

    override suspend fun capabilities(): ServerCapabilities =
        withContext(ioDispatcher) {
            val capabilities = call { it.capabilities() }
            settingsStore.recordContact(capabilities)
            capabilities
        }

    override suspend fun postDiagnosticEvents(
        events: List<com.dedtsss.catawg.core.diagnostics.DiagnosticEvent>
    ) = call { it.postDiagnosticEvents(events) }

    override suspend fun incidents(range: TimeRange): IncidentsResponse = call {
        it.incidents(range)
    }

    override suspend fun diagnosticBundle(range: TimeRange): ByteArray = call {
        it.diagnosticBundle(range)
    }

    override suspend fun validateConfig(
        request: ConfigValidationRequest
    ): ConfigValidationResponse = call { it.validateConfig(request) }

    override suspend fun metricsCompare(
        changeAt: String,
        windowHours: Int,
    ): MetricsCompareResponse = call { it.metricsCompare(changeAt, windowHours) }

    override suspend fun aiChat(request: CatAiChatRequest) = call { it.aiChat(request) }

    override fun revokeLocalCredentials() {
        credentials.clear()
        applicationScope.launch(ioDispatcher) { settingsStore.clearPairing() }
    }
}

/**
 * Server-backed optional AI provider. It has no provider key and never applies networking changes.
 */
class CatServerAiProvider(
    private val client: CatServerClient,
    private val settingsStore: CatServerSettingsStore,
    private val credentials: CatServerCredentialStore,
) : CatAiProvider {
    override val available: Boolean
        get() = credentials.read() != null

    override suspend fun ask(request: AiAssistantRequest): AiAssistantResponse? {
        val settings = settingsStore.read()
        if (!available || settings.capabilities?.features?.aiGateway != true) return null
        val response =
            client.aiChat(
                CatAiChatRequest(
                    message = request.message,
                    incidentId = request.context["incidentId"],
                    memoryEnabled = settings.memoryEnabled,
                )
            ) ?: return null
        return AiAssistantResponse(
            message = response.message,
            recommendations =
                response.recommendations.map { text ->
                    Recommendation(
                        summary = text,
                        rationale = "Candidate advice returned by the paired Cat Server",
                    )
                },
        )
    }
}

object CatServerErrorMapper {
    fun code(error: Throwable): String {
        val chain = errorChain(error)
        val stage = chain.firstNotNullOfOrNull { it as? CatServerOperationException }?.stage
        val credentialFailure =
            chain.firstNotNullOfOrNull { it as? CatCredentialPersistenceFailure }
        val response = chain.firstNotNullOfOrNull { it as? ResponseException }
        return when {
            chain.any { it is SSLPeerUnverifiedException } -> "HOSTNAME_MISMATCH"
            chain.any {
                it is CertificateException ||
                    it.message.orEmpty().contains("fingerprint", ignoreCase = true)
            } -> "TLS_FINGERPRINT_MISMATCH"
            response?.response?.status?.value in 401..403 -> "PAIRING_EXPIRED_OR_REVOKED"
            response?.response?.status?.value == 404 || response?.response?.status?.value == 406 ->
                "INCOMPATIBLE_PROTOCOL"
            credentialFailure != null -> credentialFailure.code
            stage != null -> stage.code
            chain.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is java.net.SocketTimeoutException
            } -> "SERVER_UNREACHABLE"
            error.message.orEmpty().contains("not configured", ignoreCase = true) ->
                "NOT_CONFIGURED"
            error.message.orEmpty().contains("fingerprint", ignoreCase = true) ->
                "TLS_FINGERPRINT_REQUIRED"
            else -> "SERVER_ERROR"
        }
    }

    fun userMessage(error: Throwable): String =
        when (code(error)) {
            "HEALTH_FAILED" ->
                "Cat Server health check failed. Re-check the HTTPS endpoint and certificate fingerprint."
            "PAIRING_START_FAILED" ->
                "Cat pairing could not start. Generate a fresh one-time bootstrap payload and try again."
            "PAIRING_COMPLETE_FAILED" ->
                "Cat pairing could not complete. Generate a fresh one-time bootstrap payload and try again."
            "CREDENTIAL_PERSISTENCE_FAILED" ->
                "Cat Server accepted pairing, but Android could not securely save its credential. Forget Cat Server, restart the app, then pair with a fresh payload."
            "KEYSTORE_KEY_FAILED" ->
                "Android Keystore could not load or create the Cat credential key. Pairing was not saved."
            "ENCRYPT_FAILED" ->
                "Android could not encrypt the Cat credential. Pairing was not saved."
            "CREDENTIAL_WRITE_FAILED" ->
                "Android could not write the encrypted Cat credential. Pairing was not saved."
            "CREDENTIAL_READBACK_FAILED" ->
                "Android wrote the Cat credential but its immediate verification failed. Pairing was not saved."
            "CREDENTIAL_READ_FAILED" ->
                "Android could not read the encrypted Cat credential. Local state was reset safely."
            "DECRYPT_FAILED" ->
                "Android could not decrypt the Cat credential. Local state was reset safely."
            "PAIRING_SETTINGS_FAILED" ->
                "Cat Server credential was saved, but Android could not record the paired-device settings. Restart the app and check Cat Server status."
            "CAPABILITIES_FAILED" ->
                "Cat Server pairing succeeded, but its capabilities could not be read. The paired device is retained; retry the health check."
            "NOT_CONFIGURED" ->
                "Cat Server is not configured. Enter an HTTPS server and verified certificate fingerprint."
            "TLS_FINGERPRINT_REQUIRED" ->
                "A SHA-256 certificate fingerprint is required before connecting."
            "TLS_FINGERPRINT_MISMATCH" ->
                "TLS certificate fingerprint mismatch. Re-check the fingerprint out of band."
            "HOSTNAME_MISMATCH" ->
                "TLS hostname verification failed. Use the hostname present in the server certificate."
            "PAIRING_EXPIRED_OR_REVOKED" ->
                "The pairing code or device token expired/revoked. Forget and pair again."
            "INCOMPATIBLE_PROTOCOL" ->
                "The server does not expose compatible Cat Protocol v1 endpoints."
            "SERVER_UNREACHABLE" ->
                "Cat Server is unreachable. Check the host, port, firewall and network."
            else -> "Cat Server request failed. Check the server status and try again."
        }

    private fun errorChain(error: Throwable): List<Throwable> = buildList {
        var current: Throwable? = error
        while (current != null && none { it === current }) {
            add(current)
            current = current.cause
        }
    }

    private val CatServerOperationStage.code: String
        get() =
            when (this) {
                CatServerOperationStage.HEALTH -> "HEALTH_FAILED"
                CatServerOperationStage.PAIRING_START -> "PAIRING_START_FAILED"
                CatServerOperationStage.PAIRING_COMPLETE -> "PAIRING_COMPLETE_FAILED"
                CatServerOperationStage.CREDENTIAL_PERSISTENCE -> "CREDENTIAL_PERSISTENCE_FAILED"
                CatServerOperationStage.PAIRING_SETTINGS -> "PAIRING_SETTINGS_FAILED"
                CatServerOperationStage.CAPABILITIES -> "CAPABILITIES_FAILED"
            }
}

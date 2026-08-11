package com.zaneschepke.wireguardautotunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dedtsss.catawg.core.protocol.CatBootstrapParser
import com.dedtsss.catawg.core.protocol.CatBootstrapPayload
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.PairingBootstrap
import com.dedtsss.catawg.core.protocol.normalizeCertificateFingerprint
import com.zaneschepke.wireguardautotunnel.cat.server.CatPairingImportStore
import com.zaneschepke.wireguardautotunnel.cat.server.CatServerErrorMapper
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import com.zaneschepke.wireguardautotunnel.ui.state.CatPairingPreview
import com.zaneschepke.wireguardautotunnel.ui.state.CatPairingStep
import com.zaneschepke.wireguardautotunnel.ui.state.CatServerAction
import com.zaneschepke.wireguardautotunnel.ui.state.CatServerConnectionStatus
import com.zaneschepke.wireguardautotunnel.ui.state.CatServerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CatServerViewModel(
    private val client: CatServerClient,
    private val settingsStore: CatServerSettingsStore,
    private val credentials: CatServerCredentialStore,
    private val pairingImports: CatPairingImportStore,
) : ViewModel() {
    private val _state = MutableStateFlow(CatServerUiState())
    val state = _state.asStateFlow()

    // A pairing token has intentionally shorter lifetime than the screen state. It must not enter
    // saved state, preferences, diagnostics or UI snapshots.
    private var importedBootstrap: CatBootstrapPayload? = null

    init {
        viewModelScope.launch {
            settingsStore.flow.collect { settings ->
                _state.update { current ->
                    val status = deriveStatus(settings, current.status)
                    current.copy(
                        settings = settings,
                        capabilities = settings.capabilities ?: current.capabilities,
                        status = status,
                        pairingStep =
                            if (status == CatServerConnectionStatus.CONNECTED) {
                                CatPairingStep.CONNECTED
                            } else {
                                current.pairingStep
                            },
                    )
                }
            }
        }
        viewModelScope.launch {
            pairingImports.pendingPayload.filterNotNull().collect { rawPayload ->
                importBootstrap(rawPayload)
                pairingImports.consume(rawPayload)
            }
        }
    }

    /** Parses QR, share-sheet and deep-link input without displaying or persisting its token. */
    fun importBootstrap(rawPayload: String) {
        runCatching { CatBootstrapParser.parse(rawPayload) }
            .onSuccess { payload ->
                importedBootstrap = payload
                _state.update {
                    it.copy(
                        errorCode = null,
                        pairingPreview = CatPairingPreview(server = payload.server),
                        pairingStep = CatPairingStep.VERIFY,
                        lastAction = CatServerAction.PAIRING_IMPORTED,
                    )
                }
            }
            .onFailure {
                importedBootstrap = null
                _state.update { state ->
                    state.copy(
                        errorCode = "PAIRING_PAYLOAD_INVALID",
                        pairingPreview = null,
                        pairingStep = CatPairingStep.START,
                    )
                }
            }
    }

    fun discardImportedBootstrap() {
        importedBootstrap = null
        _state.update {
            it.copy(pairingPreview = null, pairingStep = CatPairingStep.START, errorCode = null)
        }
    }

    fun verifyImportedBootstrap() {
        val payload = importedBootstrap ?: return missingImportedBootstrap()
        verifyEndpoint(payload.server, payload.certificateFingerprint, guided = true)
    }

    fun pairImported(deviceName: String) {
        val payload = importedBootstrap ?: return missingImportedBootstrap()
        pair(payload.server, payload.certificateFingerprint, payload.bootstrapToken, deviceName)
    }

    fun healthCheck(serverUrl: String, fingerprint: String) {
        verifyEndpoint(serverUrl, fingerprint, guided = false)
    }

    private fun verifyEndpoint(serverUrl: String, fingerprint: String, guided: Boolean) {
        runAction {
            settingsStore.saveEndpoint(serverUrl, fingerprint)
            val health = client.health()
            val capabilities = if (credentials.read() != null) client.capabilities() else null
            _state.update {
                it.copy(
                    health = health,
                    capabilities = capabilities ?: it.capabilities,
                    status =
                        if (credentials.read() != null) CatServerConnectionStatus.CONNECTED
                        else CatServerConnectionStatus.CONFIGURED,
                    pairingStep = if (guided) CatPairingStep.PAIR else it.pairingStep,
                    lastAction = CatServerAction.SERVER_VERIFIED,
                )
            }
        }
    }

    fun pair(serverUrl: String, fingerprint: String, bootstrapToken: String, deviceName: String) {
        runAction {
            require(bootstrapToken.isNotBlank()) { "Bootstrap token is required" }
            require(deviceName.isNotBlank()) { "Device name is required" }
            settingsStore.saveEndpoint(serverUrl, fingerprint)
            val health = client.health()
            client.pair(
                deviceName.trim(),
                PairingBootstrap(
                    certificateFingerprint = normalizeCertificateFingerprint(fingerprint),
                    bootstrapToken = bootstrapToken.trim(),
                ),
            )
            // Pairing is committed before capabilities are requested. A transient capabilities
            // failure must not turn a securely paired device back into a generic ERROR state.
            val capabilitiesResult = runCatching { client.capabilities() }
            importedBootstrap = null
            _state.update {
                it.copy(
                    health = health,
                    capabilities = capabilitiesResult.getOrNull() ?: it.capabilities,
                    status = CatServerConnectionStatus.CONNECTED,
                    errorCode = capabilitiesResult.exceptionOrNull()?.let(CatServerErrorMapper::code),
                    pairingPreview = null,
                    pairingStep = CatPairingStep.CONNECTED,
                    lastAction = CatServerAction.PAIRED,
                )
            }
        }
    }

    fun refresh() {
        val settings = _state.value.settings
        if (!settings.isConfigured) return
        runAction {
            val health = client.health()
            val capabilities = if (credentials.read() != null) client.capabilities() else null
            _state.update {
                it.copy(
                    health = health,
                    capabilities = capabilities ?: it.capabilities,
                    status =
                        if (credentials.read() != null) CatServerConnectionStatus.CONNECTED
                        else CatServerConnectionStatus.CONFIGURED,
                    lastAction = CatServerAction.REFRESHED,
                )
            }
        }
    }

    fun forget() {
        importedBootstrap = null
        client.revokeLocalCredentials()
        _state.update {
            it.copy(
                status = CatServerConnectionStatus.NOT_CONFIGURED,
                health = null,
                capabilities = null,
                errorCode = null,
                pairingPreview = null,
                pairingStep = CatPairingStep.START,
                lastAction = CatServerAction.FORGOTTEN,
            )
        }
    }

    fun setDiagnosticsUploadEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDiagnosticsUploadEnabled(enabled) }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMemoryEnabled(enabled) }
    }

    fun clearError() = _state.update { it.copy(errorCode = null) }

    fun reportError(code: String) = _state.update { it.copy(errorCode = code) }

    private fun missingImportedBootstrap() {
        _state.update { it.copy(errorCode = "PAIRING_PAYLOAD_REQUIRED") }
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorCode = null) }
            runCatching { block() }.onFailure { error ->
                _state.update {
                    it.copy(
                        status = CatServerConnectionStatus.ERROR,
                        errorCode = CatServerErrorMapper.code(error),
                    )
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun deriveStatus(
        settings: com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettings,
        current: CatServerConnectionStatus,
    ): CatServerConnectionStatus =
        when {
            !settings.isConfigured -> CatServerConnectionStatus.NOT_CONFIGURED
            current == CatServerConnectionStatus.ERROR && settings.lastErrorCode != null -> current
            credentials.read() != null && settings.isPaired -> CatServerConnectionStatus.CONNECTED
            else -> CatServerConnectionStatus.CONFIGURED
        }
}

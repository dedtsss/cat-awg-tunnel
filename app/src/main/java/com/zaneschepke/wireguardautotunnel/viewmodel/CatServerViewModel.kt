package com.zaneschepke.wireguardautotunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.PairingBootstrap
import com.dedtsss.catawg.core.protocol.normalizeCertificateFingerprint
import com.zaneschepke.wireguardautotunnel.cat.server.CatServerErrorMapper
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import com.zaneschepke.wireguardautotunnel.ui.state.CatServerConnectionStatus
import com.zaneschepke.wireguardautotunnel.ui.state.CatServerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CatServerViewModel(
    private val client: CatServerClient,
    private val settingsStore: CatServerSettingsStore,
    private val credentials: CatServerCredentialStore,
) : ViewModel() {
    private val _state = MutableStateFlow(CatServerUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.flow.collect { settings ->
                _state.update { current ->
                    current.copy(
                        settings = settings,
                        capabilities = settings.capabilities ?: current.capabilities,
                        status = deriveStatus(settings, current.status),
                    )
                }
            }
        }
    }

    fun healthCheck(serverUrl: String, fingerprint: String) {
        runAction("health") {
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
                    lastAction = "Health check succeeded",
                )
            }
        }
    }

    fun pair(serverUrl: String, fingerprint: String, bootstrapToken: String, deviceName: String) {
        runAction("pair") {
            require(bootstrapToken.isNotBlank()) { "Bootstrap token is required" }
            require(deviceName.isNotBlank()) { "Device name is required" }
            settingsStore.saveEndpoint(serverUrl, fingerprint)
            val health = client.health()
            val pairing =
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
            _state.update {
                it.copy(
                    health = health,
                    capabilities = capabilitiesResult.getOrNull() ?: it.capabilities,
                    status = CatServerConnectionStatus.CONNECTED,
                    error = capabilitiesResult.exceptionOrNull()?.let(CatServerErrorMapper::userMessage),
                    lastAction = "Paired device ${pairing.device.name}",
                )
            }
        }
    }

    fun refresh() {
        val settings = _state.value.settings
        if (!settings.isConfigured) return
        runAction("refresh") {
            val health = client.health()
            val capabilities = if (credentials.read() != null) client.capabilities() else null
            _state.update {
                it.copy(
                    health = health,
                    capabilities = capabilities ?: it.capabilities,
                    status =
                        if (credentials.read() != null) CatServerConnectionStatus.CONNECTED
                        else CatServerConnectionStatus.CONFIGURED,
                    lastAction = "Server status refreshed",
                )
            }
        }
    }

    fun forget() {
        client.revokeLocalCredentials()
        viewModelScope.launch { settingsStore.clearPairing() }
        _state.update {
            it.copy(
                status = CatServerConnectionStatus.NOT_CONFIGURED,
                health = null,
                capabilities = null,
                error = null,
                lastAction = "Local pairing forgotten",
            )
        }
    }

    fun setDiagnosticsUploadEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDiagnosticsUploadEnabled(enabled) }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMemoryEnabled(enabled) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun runAction(action: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { block() }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            status = CatServerConnectionStatus.ERROR,
                            error = CatServerErrorMapper.userMessage(error),
                            lastAction = action,
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

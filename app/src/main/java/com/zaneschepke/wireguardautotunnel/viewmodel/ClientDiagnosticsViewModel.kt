package com.zaneschepke.wireguardautotunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportBuilder
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportBundle
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportWindow
import com.dedtsss.catawg.core.diagnostics.DiagnosticStore
import com.dedtsss.catawg.core.protocol.CatAiChatRequest
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.MetricsCompareResponse
import com.dedtsss.catawg.core.protocol.TimeRange
import com.zaneschepke.wireguardautotunnel.cat.diagnostics.CatDiagnosticsSyncCoordinator
import com.zaneschepke.wireguardautotunnel.cat.server.CatServerErrorMapper
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import com.zaneschepke.wireguardautotunnel.ui.state.ClientDiagnosticsUiState
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android UI adapter for the portable, locally sanitized diagnostic-export bundle. */
class ClientDiagnosticsViewModel(
    private val store: DiagnosticStore,
    private val client: CatServerClient,
    private val settingsStore: CatServerSettingsStore,
    private val credentials: CatServerCredentialStore,
    private val syncCoordinator: CatDiagnosticsSyncCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(ClientDiagnosticsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.flow.collect { settings ->
                _state.update {
                    it.copy(
                        serverSettings = settings,
                        serverEvidenceAvailable = it.serverEvidenceAvailable && settings.isPaired,
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val currentSettings = settingsStore.read()
            runCatching {
                    withContext(Dispatchers.IO) {
                        val now = Instant.now()
                        val from = now.minus(48, ChronoUnit.HOURS)
                        store.events(from, now) to store.incidents(from, now)
                    }
                }
                .onSuccess { (events, incidents) ->
                    _state.update {
                        it.copy(
                            eventCount = events.size,
                            incidents =
                                incidents.sortedByDescending { incident -> incident.startAt },
                            serverSettings = currentSettings,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Could not load diagnostics",
                        )
                    }
                }
            loadServerIncidents()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.update { it.copy(syncBusy = true, serverError = null) }
            runCatching { syncCoordinator.sync() }
                .onFailure { error ->
                    _state.update { it.copy(serverError = CatServerErrorMapper.userMessage(error)) }
                }
                .onSuccess { _state.update { it.copy(serverError = null) } }
            _state.update { it.copy(syncBusy = false) }
            refresh()
        }
    }

    fun askAi(message: String, incidentId: String? = null) {
        if (message.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(aiBusy = true, error = null) }
            runCatching {
                    val settings = settingsStore.read()
                    require(settings.capabilities?.features?.aiGateway == true) {
                        "AI Assistant is disabled by the paired server"
                    }
                    client.aiChat(
                        CatAiChatRequest(
                            message = message.trim(),
                            incidentId = incidentId,
                            memoryEnabled = settings.memoryEnabled,
                        )
                    ) ?: error("AI Assistant returned no response")
                }
                .onSuccess { response ->
                    _state.update {
                        it.copy(
                            aiResponse = response.message,
                            aiRecommendations = response.recommendations,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = CatServerErrorMapper.userMessage(error)) }
                }
            _state.update { it.copy(aiBusy = false) }
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMemoryEnabled(enabled) }
    }

    fun setDiagnosticsUploadEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDiagnosticsUploadEnabled(enabled) }
    }

    fun loadReliability(changeAt: String) {
        viewModelScope.launch {
            val settings = settingsStore.read()
            if (settings.isPaired && credentials.read() != null) {
                runCatching { client.metricsCompare(changeAt) }
                    .onSuccess { metrics ->
                        _state.update { it.copy(metrics = metrics, metricsSource = "Cat Server") }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                metrics = null,
                                metricsSource =
                                    "Server metrics unavailable: ${CatServerErrorMapper.code(error)}",
                            )
                        }
                    }
            } else {
                val localMetrics = localReliability(changeAt)
                _state.update {
                    it.copy(
                        metrics = localMetrics,
                        metricsSource = "Local incidents; causality is not proven",
                    )
                }
            }
        }
    }

    suspend fun downloadServerBundle(window: DiagnosticExportWindow): ByteArray {
        val to = Instant.now()
        val from = to.minus(window.minutes, ChronoUnit.MINUTES)
        return withContext(Dispatchers.IO) {
            client.diagnosticBundle(TimeRange(from.toString(), to.toString()))
        }
    }

    suspend fun export(window: DiagnosticExportWindow): DiagnosticExportBundle =
        withContext(Dispatchers.IO) {
            val to = Instant.now()
            val from = to.minus(window.minutes, ChronoUnit.MINUTES)
            DiagnosticExportBuilder.build(
                events = store.events(from, to),
                incidents = store.incidents(from, to),
                from = from,
                to = to,
            )
        }

    fun reportError(message: String) = _state.update { it.copy(error = message) }

    private suspend fun loadServerIncidents() {
        val settings = settingsStore.read()
        if (!settings.isPaired || credentials.read() == null) {
            _state.update {
                it.copy(
                    serverIncidents = emptyList(),
                    serverEvidenceAvailable = false,
                    serverError = null,
                )
            }
            return
        }
        val now = Instant.now()
        val from = now.minus(48, ChronoUnit.HOURS)
        runCatching { client.incidents(TimeRange(from.toString(), now.toString())) }
            .onSuccess { response ->
                _state.update {
                    it.copy(
                        serverIncidents =
                            response.incidents.sortedByDescending { incident -> incident.startAt },
                        serverEvidenceAvailable = true,
                        serverError = null,
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        serverIncidents = emptyList(),
                        serverEvidenceAvailable = false,
                        serverError = CatServerErrorMapper.userMessage(error),
                    )
                }
            }
    }

    private suspend fun localReliability(changeAt: String): MetricsCompareResponse {
        val change = runCatching { Instant.parse(changeAt) }.getOrElse { Instant.now() }
        val before = change.minus(24, ChronoUnit.HOURS)
        val after = change.plus(24, ChronoUnit.HOURS)
        val beforeCount = store.incidents(before, change).size
        val afterCount = store.incidents(change, after).size
        return MetricsCompareResponse(
            changeAt = change.toString(),
            before = com.dedtsss.catawg.core.protocol.ReliabilityMetrics(incidents = beforeCount),
            after = com.dedtsss.catawg.core.protocol.ReliabilityMetrics(incidents = afterCount),
            windowSeconds = 86_400,
        )
    }
}

package com.zaneschepke.wireguardautotunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportBuilder
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportBundle
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportWindow
import com.dedtsss.catawg.core.diagnostics.DiagnosticStore
import com.zaneschepke.wireguardautotunnel.ui.state.ClientDiagnosticsUiState
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android UI adapter for the portable, locally sanitized diagnostic-export bundle. */
class ClientDiagnosticsViewModel(private val store: DiagnosticStore) : ViewModel() {
    private val _state = MutableStateFlow(ClientDiagnosticsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                    withContext(Dispatchers.IO) {
                        val now = Instant.now()
                        val from = now.minus(48, ChronoUnit.HOURS)
                        store.events(from, now) to store.incidents(from, now)
                    }
                }
                .onSuccess { (events, incidents) ->
                    _state.value =
                        ClientDiagnosticsUiState(
                            eventCount = events.size,
                            incidents = incidents.sortedByDescending { it.startAt },
                        )
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.message ?: "Could not load diagnostics") }
                }
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
}

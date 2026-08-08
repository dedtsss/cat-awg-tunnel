package com.zaneschepke.wireguardautotunnel.ui.state

import com.dedtsss.catawg.core.diagnostics.Incident

data class ClientDiagnosticsUiState(
    val eventCount: Int = 0,
    val incidents: List<Incident> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

package com.zaneschepke.wireguardautotunnel.ui.state

import com.dedtsss.catawg.core.diagnostics.Incident
import com.dedtsss.catawg.core.protocol.MetricsCompareResponse
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettings

data class ClientDiagnosticsUiState(
    val eventCount: Int = 0,
    val incidents: List<Incident> = emptyList(),
    val serverIncidents: List<Incident> = emptyList(),
    val serverSettings: CatServerSettings = CatServerSettings(),
    val serverEvidenceAvailable: Boolean = false,
    val serverError: String? = null,
    val syncBusy: Boolean = false,
    val aiBusy: Boolean = false,
    val aiResponse: String? = null,
    val aiRecommendations: List<String> = emptyList(),
    val metrics: MetricsCompareResponse? = null,
    val metricsSource: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

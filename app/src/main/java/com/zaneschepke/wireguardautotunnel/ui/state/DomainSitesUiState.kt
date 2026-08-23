package com.zaneschepke.wireguardautotunnel.ui.state

import com.dedtsss.catawg.core.routing.DomainDiagnosis
import com.dedtsss.catawg.core.routing.DomainRule

data class DomainSitesUiState(
    val rules: List<DomainRule> = emptyList(),
    val globalRules: List<DomainRule> = emptyList(),
    val isGlobalScope: Boolean = false,
    val diagnosis: DomainDiagnosis? = null,
    val error: String? = null,
    val isWorking: Boolean = false,
)

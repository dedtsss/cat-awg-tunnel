package com.zaneschepke.wireguardautotunnel.ui.state

import com.dedtsss.catawg.core.configurator.ConfigProtocol
import com.dedtsss.catawg.core.configurator.ConfigurationChange
import com.dedtsss.catawg.core.configurator.PublicConfigProfile
import com.dedtsss.catawg.core.configurator.ValidationResult
import com.dedtsss.catawg.core.protocol.ConfigValidationResponse
import com.dedtsss.catawg.core.protocol.MetricsCompareResponse

data class ConfiguratorUiState(
    val rawText: String = "",
    val profileName: String = "Candidate",
    val protocol: ConfigProtocol = ConfigProtocol.AWG2,
    val parsedProfile: PublicConfigProfile? = null,
    val validation: ValidationResult? = null,
    val profiles: List<PublicConfigProfile> = emptyList(),
    val changes: List<ConfigurationChange> = emptyList(),
    val comparison: Map<String, String> = emptyMap(),
    val serverValidation: ConfigValidationResponse? = null,
    val serverError: String? = null,
    val aiResponse: String? = null,
    val aiRecommendations: List<String> = emptyList(),
    val reliability: MetricsCompareResponse? = null,
    val reliabilitySource: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

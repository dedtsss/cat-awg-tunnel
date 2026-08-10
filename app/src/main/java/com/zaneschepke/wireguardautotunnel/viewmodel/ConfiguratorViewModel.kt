package com.zaneschepke.wireguardautotunnel.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dedtsss.catawg.core.configurator.AwgCapabilities
import com.dedtsss.catawg.core.configurator.AwgConfigGenerator
import com.dedtsss.catawg.core.configurator.AwgConfigParser
import com.dedtsss.catawg.core.configurator.AwgConfigValidator
import com.dedtsss.catawg.core.configurator.ConfigProtocol
import com.dedtsss.catawg.core.configurator.ConfigurationChange
import com.dedtsss.catawg.core.configurator.ValidationIssue
import com.dedtsss.catawg.core.configurator.ValidationResult
import com.dedtsss.catawg.core.configurator.toPublic
import com.dedtsss.catawg.core.protocol.CatAiChatRequest
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.ConfigValidationRequest
import com.dedtsss.catawg.core.protocol.MetricsCompareResponse
import com.dedtsss.catawg.core.protocol.ReliabilityMetrics
import com.dedtsss.catawg.core.protocol.isSecretBearingConfigKey
import com.zaneschepke.wireguardautotunnel.cat.server.CatServerErrorMapper
import com.zaneschepke.wireguardautotunnel.data.cat.CatConfigProfileStore
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.state.ConfiguratorUiState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConfiguratorViewModel(
    private val profileStore: CatConfigProfileStore,
    private val client: CatServerClient,
    private val settingsStore: CatServerSettingsStore,
    private val credentials: CatServerCredentialStore,
    private val context: Context,
) : ViewModel() {
    private val parser = AwgConfigParser()
    private val validator = AwgConfigValidator(parser)
    private val generator = AwgConfigGenerator(validator)
    private val json = Json { encodeDefaults = true }
    private val _state = MutableStateFlow(ConfiguratorUiState())
    val state = _state.asStateFlow()

    init {
        reloadHistory()
    }

    fun setRawText(value: String) = _state.update { it.copy(rawText = value, error = null) }

    fun setProfileName(value: String) = _state.update { it.copy(profileName = value) }

    fun setProtocol(protocol: ConfigProtocol) = _state.update {
        it.copy(protocol = protocol, validation = null, parsedProfile = null)
    }

    fun createCandidate() = _state.update {
        it.copy(
            rawText = "[Interface]\n\n[Peer]\n",
            profileName = context.getString(R.string.configurator_default_candidate),
            validation = null,
            parsedProfile = null,
            serverValidation = null,
            error = null,
        )
    }

    fun validateLocally() {
        val snapshot = _state.value
        viewModelScope.launch {
            val result =
                try {
                    val document = parser.parse(snapshot.rawText)
                    val profile =
                        generator.candidate(
                            snapshot.profileName.ifBlank {
                                context.getString(R.string.configurator_default_candidate)
                            },
                            snapshot.protocol,
                            document,
                            capabilities(),
                        )
                    profile to profile.validation
                } catch (error: Throwable) {
                    null to
                        ValidationResult(
                            listOf(
                                ValidationIssue(
                                    "PARSE_ERROR",
                                    context.getString(R.string.configurator_error_parse),
                                )
                            )
                        )
                }
            val public = result.first?.toPublic()
            _state.update {
                it.copy(
                    parsedProfile = public,
                    validation = result.second,
                    serverValidation = null,
                    error = null,
                )
            }
        }
    }

    fun saveCandidate() {
        viewModelScope.launch {
            val profile =
                _state.value.parsedProfile
                    ?: run {
                        validateLocally()
                        return@launch
                    }
            if (profile.parameters.keys.any(::isSecretBearingConfigKey)) {
                _state.update {
                    it.copy(
                        error = context.getString(R.string.configurator_error_secret)
                    )
                }
                return@launch
            }
            runBusy {
                profileStore.save(profile)
                profileStore.recordChange(
                    ConfigurationChange(
                        newProfileId = profile.id,
                        reason = context.getString(R.string.configurator_change_saved),
                        recommendationSource = "user",
                    )
                )
                reloadHistoryInternal()
            }
        }
    }

    fun validateOnServer() {
        viewModelScope.launch {
            val profile = _state.value.parsedProfile
            if (profile == null) {
                _state.update {
                    it.copy(
                        serverError =
                            context.getString(R.string.configurator_error_validate_first)
                    )
                }
                return@launch
            }
            val settings = settingsStore.read()
            if (!settings.isPaired || credentials.read() == null) {
                _state.update {
                    it.copy(serverError = context.getString(R.string.configurator_error_pair_first))
                }
                return@launch
            }
            runBusy {
                suspendResult {
                        client.validateConfig(ConfigValidationRequest(publicProfile = profile))
                    }
                    .onSuccess { response ->
                        _state.update { it.copy(serverValidation = response, serverError = null) }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(serverError = CatServerErrorMapper.userMessage(context, error))
                        }
                    }
            }
        }
    }

    fun compare(profileId: String) {
        viewModelScope.launch {
            val selected =
                profileStore.profiles().firstOrNull { it.id == profileId } ?: return@launch
            val baseline = profileStore.profiles().firstOrNull { it.id != profileId }
            val keys =
                (selected.parameters.keys + (baseline?.parameters?.keys ?: emptySet()))
                    .toSortedSet()
            _state.update {
                it.copy(
                    comparison =
                        keys.associateWith { key ->
                            val before = baseline?.parameters?.get(key) ?: "—"
                            val after = selected.parameters[key] ?: "—"
                            "$before → $after"
                        }
                )
            }
        }
    }

    fun askAi() {
        viewModelScope.launch {
            val profile = _state.value.parsedProfile ?: return@launch
            val settings = settingsStore.read()
            if (
                !settings.isPaired ||
                    credentials.read() == null ||
                    settings.capabilities?.features?.aiGateway != true
            ) {
                _state.update {
                    it.copy(error = context.getString(R.string.configurator_error_ai_disabled))
                }
                return@launch
            }
            runBusy {
                suspendResult {
                        client.aiChat(
                            CatAiChatRequest(
                                message =
                                    "Explain this public AWG candidate and suggest safe candidate-only improvements: ${json.encodeToString(profile)}",
                                memoryEnabled = settings.memoryEnabled,
                            )
                        ) ?: error("AI Assistant returned no response")
                    }
                    .onSuccess { response ->
                        _state.update {
                            it.copy(
                                aiResponse = response.message,
                                aiRecommendations = response.recommendations,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(error = CatServerErrorMapper.userMessage(context, error))
                        }
                    }
            }
        }
    }

    fun loadReliability(profileId: String) {
        viewModelScope.launch {
            val change = profileStore.changes().firstOrNull { it.newProfileId == profileId }
            val changeAt =
                change?.at
                    ?: profileStore.profiles().firstOrNull { it.id == profileId }?.updatedAt
                    ?: Instant.now().toString()
            val settings = settingsStore.read()
            if (settings.isPaired && credentials.read() != null) {
                suspendResult { client.metricsCompare(changeAt) }
                    .onSuccess { metrics ->
                        _state.update {
                            it.copy(
                                reliability = metrics,
                                reliabilitySource =
                                    context.getString(R.string.configurator_server_metrics),
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                reliability = null,
                                reliabilitySource = CatServerErrorMapper.userMessage(context, error),
                            )
                        }
                    }
            } else {
                _state.update {
                    it.copy(
                        reliability =
                            MetricsCompareResponse(
                                changeAt = changeAt,
                                before =
                                    ReliabilityMetrics(
                                        sampleNote =
                                            context.getString(R.string.configurator_pair_for_metrics)
                                    ),
                                after =
                                    ReliabilityMetrics(
                                        sampleNote =
                                            context.getString(R.string.configurator_pair_for_metrics)
                                    ),
                                windowSeconds = 86_400,
                            ),
                        reliabilitySource =
                            context.getString(R.string.configurator_local_metrics_note),
                    )
                }
            }
        }
    }

    fun reloadHistory() {
        viewModelScope.launch { reloadHistoryInternal() }
    }

    private suspend fun reloadHistoryInternal() {
        val profiles = withContext(Dispatchers.IO) { profileStore.profiles() }
        val changes = withContext(Dispatchers.IO) { profileStore.changes() }
        _state.update {
            it.copy(
                profiles = profiles.sortedByDescending { profile -> profile.updatedAt },
                changes = changes,
            )
        }
    }

    private suspend fun capabilities(): AwgCapabilities {
        val engines = settingsStore.read().capabilities?.engines.orEmpty()
        return AwgCapabilities(
            serverWireguard = engines["wireguard"]?.supported,
            serverAwg2 = engines["awg2"]?.supported,
            serverAwg3 = engines["awg3"]?.supported,
        )
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        try {
            block()
        } catch (error: Throwable) {
            _state.update { it.copy(error = CatServerErrorMapper.userMessage(context, error)) }
        }
        _state.update { it.copy(busy = false) }
    }

    private suspend fun <T> suspendResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: Throwable) {
            Result.failure(error)
        }
}

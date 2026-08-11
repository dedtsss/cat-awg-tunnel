package com.zaneschepke.wireguardautotunnel.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dedtsss.catawg.core.configurator.AwgCapabilities
import com.dedtsss.catawg.core.configurator.AwgConfigGenerator
import com.dedtsss.catawg.core.configurator.AwgConfigParser
import com.dedtsss.catawg.core.configurator.AwgConfigValidator
import com.dedtsss.catawg.core.configurator.ConfigProtocol
import com.dedtsss.catawg.core.configurator.ConfigurationAdvisor
import com.dedtsss.catawg.core.configurator.ConfigurationAdvisorContext
import com.dedtsss.catawg.core.configurator.ConfigurationChange
import com.dedtsss.catawg.core.configurator.ConfigurationExperiment
import com.dedtsss.catawg.core.configurator.ConfigurationFingerprint
import com.dedtsss.catawg.core.configurator.ConfigurationResult
import com.dedtsss.catawg.core.configurator.DeterministicConfigurationAdvisor
import com.dedtsss.catawg.core.configurator.DiagnosticSnapshotBuilder
import com.dedtsss.catawg.core.configurator.PublicConfigProfile
import com.dedtsss.catawg.core.configurator.ValidationIssue
import com.dedtsss.catawg.core.configurator.ValidationResult
import com.dedtsss.catawg.core.configurator.toPublic
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import com.dedtsss.catawg.core.diagnostics.DiagnosticStore
import com.dedtsss.catawg.core.diagnostics.Incident
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.ConfigValidationRequest
import com.dedtsss.catawg.core.protocol.MetricsCompareResponse
import com.dedtsss.catawg.core.protocol.ReliabilityMetrics
import com.dedtsss.catawg.core.protocol.isSecretBearingConfigKey
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.cat.server.CatServerErrorMapper
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.data.cat.CatConfigProfileStore
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.ui.state.ConfiguratorUiState
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All config advice is local and deterministic in this release.  The advisor is injected as an
 * interface boundary in core, but Android intentionally does not call an AI or remote shell here.
 */
class ConfiguratorViewModel(
    private val profileStore: CatConfigProfileStore,
    private val client: CatServerClient,
    private val settingsStore: CatServerSettingsStore,
    private val credentials: CatServerCredentialStore,
    private val diagnosticStore: DiagnosticStore,
    private val tunnelRepository: TunnelRepository,
    private val tunnelCoordinator: TunnelCoordinator,
    private val context: Context,
    private val advisor: ConfigurationAdvisor = DeterministicConfigurationAdvisor(),
) : ViewModel() {
    private val parser = AwgConfigParser()
    private val validator = AwgConfigValidator(parser)
    private val generator = AwgConfigGenerator(validator)
    private val _state = MutableStateFlow(ConfiguratorUiState())
    val state = _state.asStateFlow()

    init {
        reloadHistory()
    }

    fun setRawText(value: String) =
        _state.update { it.copy(rawText = value, error = null, serverValidation = null) }

    fun setProfileName(value: String) = _state.update { it.copy(profileName = value) }

    fun setProtocol(protocol: ConfigProtocol) = _state.update {
        it.copy(protocol = protocol, validation = null, parsedProfile = null, recommendations = emptyList())
    }

    fun createCandidate() = _state.update {
        it.copy(
            rawText = "[Interface]\n\n[Peer]\n",
            profileName = context.getString(R.string.configurator_default_candidate),
            validation = null,
            parsedProfile = null,
            serverValidation = null,
            recommendations = emptyList(),
            error = null,
        )
    }

    /** Text import is deliberately local: the same parser and validator handle pasted and file data. */
    fun importRawText(value: String) {
        setRawText(value)
        validateLocally()
    }

    fun exportRawText(): String = _state.value.rawText

    fun reportError(message: String) = _state.update { it.copy(error = message) }

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
                    profile.toPublic() to profile.validation
                } catch (_: Throwable) {
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
            val advice = result.first?.let { profile -> recommendationsFor(profile) }.orEmpty()
            _state.update {
                it.copy(
                    parsedProfile = result.first,
                    validation = result.second,
                    recommendations = advice,
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
                _state.update { it.copy(error = context.getString(R.string.configurator_error_secret)) }
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

    /** This is a safe, public-only server validation endpoint; it never applies a server config. */
    fun validateOnServer() {
        viewModelScope.launch {
            val profile = _state.value.parsedProfile
            if (profile == null) {
                _state.update {
                    it.copy(serverError = context.getString(R.string.configurator_error_validate_first))
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
                suspendResult { client.validateConfig(ConfigValidationRequest(publicProfile = profile)) }
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

    fun selectComparisonBaseline(profileId: String) =
        _state.update { it.copy(comparisonBaselineId = profileId, comparison = emptyMap()) }

    /** Comparison is explicit: there is no hidden "first profile" baseline. */
    fun compare(profileId: String) {
        viewModelScope.launch {
            val profiles = profileStore.profiles()
            val selected = profiles.firstOrNull { it.id == profileId } ?: return@launch
            val baselineId = _state.value.comparisonBaselineId
            val baseline = profiles.firstOrNull { it.id == baselineId }
            if (baseline == null || baseline.id == selected.id) {
                _state.update { it.copy(error = context.getString(R.string.configurator_error_choose_baseline)) }
                return@launch
            }
            val keys = (selected.parameters.keys + baseline.parameters.keys).toSortedSet()
            _state.update {
                it.copy(
                    comparison =
                        keys.associateWith { key ->
                            "${baseline.parameters[key] ?: "—"} → ${selected.parameters[key] ?: "—"}"
                        }
                )
            }
        }
    }

    fun selectTargetTunnel(tunnelId: Int) = _state.update { it.copy(selectedTunnelId = tunnelId) }

    fun requestApply() {
        val snapshot = _state.value
        when {
            snapshot.parsedProfile == null || snapshot.validation?.isValid != true -> {
                _state.update { it.copy(error = context.getString(R.string.configurator_error_validate_first)) }
            }
            snapshot.selectedTunnelId == null -> {
                _state.update { it.copy(error = context.getString(R.string.configurator_error_choose_tunnel)) }
            }
            else -> _state.update { it.copy(pendingApply = true, error = null) }
        }
    }

    fun dismissApply() = _state.update { it.copy(pendingApply = false) }

    /**
     * Applying modifies only an existing local tunnel after the dialog confirmation.  For an
     * active target, TunnelCoordinator performs its established, single-tunnel reconnect path.
     * No Android code has a remote-shell or arbitrary server-config operation.
     */
    fun confirmApply() {
        viewModelScope.launch {
            val snapshot = _state.value
            val targetId = snapshot.selectedTunnelId ?: return@launch
            val profile = snapshot.parsedProfile ?: return@launch
            if (snapshot.validation?.isValid != true) return@launch
            runBusy {
                val target = tunnelRepository.getById(targetId)
                    ?: error(context.getString(R.string.configurator_error_choose_tunnel))
                // The production tunnel parser remains the final compatibility check before save.
                val updatedTunnel = target.copy(quickConfig = snapshot.rawText)
                updatedTunnel.getConfig()

                val beforeProfile = publicProfileFor(target.quickConfig, target.name)
                val (events, incidents) = recentDiagnostics()
                val experiment =
                    ConfigurationExperiment(
                        networkContext = networkEvidence(events),
                        configFingerprint = ConfigurationFingerprint.of(profile),
                        changedParameters = ConfigurationFingerprint.changedParameters(beforeProfile, profile),
                        beforeDiagnostics = DiagnosticSnapshotBuilder.from(events, incidents),
                        result = "APPLIED_PENDING_OBSERVATION",
                        userAccepted = true,
                        note = "Applied locally after explicit confirmation",
                    )

                tunnelRepository.save(updatedTunnel)
                profileStore.save(profile)
                profileStore.recordExperiment(experiment)
                profileStore.recordChange(
                    ConfigurationChange(
                        newProfileId = profile.id,
                        reason = context.getString(R.string.configurator_change_applied),
                        recommendationSource = "deterministic-local",
                        result =
                            ConfigurationResult(
                                applied = true,
                                note =
                                    if (targetId in tunnelCoordinator.backendStatus.value.activeTunnels)
                                        "Active tunnel reconnect requested"
                                    else "Saved for the selected tunnel",
                            ),
                    )
                )
                if (targetId in tunnelCoordinator.backendStatus.value.activeTunnels) {
                    tunnelCoordinator.startTunnel(updatedTunnel)
                }
                _state.update { it.copy(pendingApply = false) }
                reloadHistoryInternal()
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
                                reliabilitySource = context.getString(R.string.configurator_server_metrics),
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
                                before = ReliabilityMetrics(sampleNote = context.getString(R.string.configurator_pair_for_metrics)),
                                after = ReliabilityMetrics(sampleNote = context.getString(R.string.configurator_pair_for_metrics)),
                                windowSeconds = 86_400,
                            ),
                        reliabilitySource = context.getString(R.string.configurator_local_metrics_note),
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
        val experiments = withContext(Dispatchers.IO) { profileStore.experiments() }
        val targets = withContext(Dispatchers.IO) { tunnelRepository.getAll().filterNot { it.isGlobalConfig }.map { it.toSummary() } }
        _state.update {
            it.copy(
                profiles = profiles.sortedByDescending { profile -> profile.updatedAt },
                changes = changes,
                experiments = experiments.sortedByDescending { experiment -> experiment.timestamp },
                targetTunnels = targets,
                selectedTunnelId = it.selectedTunnelId?.takeIf { selected -> targets.any { target -> target.id == selected } } ?: targets.firstOrNull()?.id,
            )
        }
    }

    private suspend fun publicProfileFor(raw: String, name: String): PublicConfigProfile? =
        try {
            generator.candidate(name, ConfigProtocol.AWG2, parser.parse(raw), capabilities()).toPublic()
        } catch (_: Throwable) {
            null
        }

    private suspend fun recommendationsFor(profile: PublicConfigProfile) =
        try {
            val (events, incidents) = recentDiagnostics()
            advisor.recommend(
                ConfigurationAdvisorContext(
                    profile = profile,
                    diagnostics = events,
                    incidents = incidents,
                    networkContext = networkEvidence(events),
                )
            )
        } catch (_: Throwable) {
            emptyList()
        }

    private suspend fun recentDiagnostics(): Pair<List<DiagnosticEvent>, List<Incident>> {
        val since = Instant.now().minus(24, ChronoUnit.HOURS)
        return diagnosticStore.events(since) to diagnosticStore.incidents(since)
    }

    /** Keep local history useful without retaining SSIDs, addresses, or raw diagnostic text. */
    private fun networkEvidence(events: List<DiagnosticEvent>): Map<String, String> =
        events.map { it.code }
            .filter { it.startsWith("NETWORK_") || it.startsWith("TUNNEL_") }
            .distinct()
            .sorted()
            .take(12)
            .takeIf { it.isNotEmpty() }
            ?.let { mapOf("recentEventCodes" to it.joinToString(",")) }
            .orEmpty()

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

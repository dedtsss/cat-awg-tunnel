package com.zaneschepke.wireguardautotunnel.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dedtsss.catawg.core.routing.DomainDiagnostics
import com.dedtsss.catawg.core.routing.DomainMatchMode
import com.dedtsss.catawg.core.routing.DomainNormalizer
import com.dedtsss.catawg.core.routing.DomainRouteTarget
import com.dedtsss.catawg.core.routing.DomainRule
import com.dedtsss.catawg.core.routing.DomainRuleRepository
import com.dedtsss.catawg.core.routing.DomainRuleSource
import com.dedtsss.catawg.core.routing.DomainResolver
import com.dedtsss.catawg.core.routing.DomainRuleCodec
import com.dedtsss.catawg.core.routing.DomainResolutionStatus
import com.zaneschepke.wireguardautotunnel.cat.routing.DomainRoutingCoordinator
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.ui.state.DomainSitesUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class DomainSitesViewModel(
    private val repository: DomainRuleRepository,
    private val coordinator: DomainRoutingCoordinator,
    private val resolver: DomainResolver,
    private val context: Context,
    private val tunnelRepository: TunnelRepository,
    val tunnelId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(DomainSitesUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.rules, tunnelRepository.globalTunnelFlow) { all, global ->
                    Triple(
                        all.filter { it.tunnelId == tunnelId },
                        all.filter { it.tunnelId == global?.id },
                        tunnelId == global?.id,
                    )
                }
                .collect { (rules, globalRules, isGlobalScope) ->
                    _state.update {
                        it.copy(
                            rules = rules,
                            globalRules = globalRules,
                            isGlobalScope = isGlobalScope,
                            isWorking = false,
                        )
                    }
                }
        }
    }

    fun add(
        domain: String,
        matchMode: DomainMatchMode,
        routeTarget: DomainRouteTarget,
        comment: String?,
        source: DomainRuleSource = DomainRuleSource.MANUAL,
    ) = runAction {
        coordinator.createAndApply(tunnelId, domain, matchMode, routeTarget, source, comment)
    }

    fun toggle(rule: DomainRule) = runAction { coordinator.updateAndApply(rule.copy(enabled = !rule.enabled)) }

    fun update(
        rule: DomainRule,
        domain: String,
        matchMode: DomainMatchMode,
        routeTarget: DomainRouteTarget,
        comment: String?,
    ) = runAction {
        coordinator.updateAndApply(
            rule.copy(
                domain = domain,
                matchMode = matchMode,
                routeTarget = routeTarget,
                comment = comment?.takeIf { it.isNotBlank() },
            )
        )
    }

    fun delete(rule: DomainRule) = runAction { coordinator.deleteAndApply(rule) }

    fun refresh() = runAction { coordinator.refreshAndRebuild(tunnelId, "manual_refresh") }

    fun diagnose(input: String) {
        viewModelScope.launch {
            val current =
                DomainNormalizer.fromSharedText(input)?.let { domain -> resolver.resolve(domain) }
            _state.update {
                it.copy(
                    diagnosis = DomainDiagnostics.explain(
                        input = input,
                        rules = repository.forTunnel(tunnelId),
                        currentResolution = current,
                    ),
                )
            }
        }
    }

    suspend fun exportJson(): String = DomainRuleCodec.toJson(repository.forTunnel(tunnelId))

    suspend fun exportTxt(): String = DomainRuleCodec.toTxt(repository.forTunnel(tunnelId))

    fun importRules(serialized: String) = runAction {
        val imported =
            runCatching { DomainRuleCodec.fromJson(serialized) }
                .getOrElse { DomainRuleCodec.fromTxt(serialized, tunnelId) }
        if (imported.isEmpty()) {
            throw IllegalArgumentException(context.getString(R.string.domain_sites_invalid_rules))
        }
        imported.forEach { rule ->
            repository.upsert(
                rule.copy(
                    id = UUID.randomUUID().toString(),
                    tunnelId = tunnelId,
                    source = DomainRuleSource.IMPORT,
                    // DNS observations are local-network dependent and intentionally regenerated.
                    resolvedIpv4 = emptyList(),
                    resolvedIpv6 = emptyList(),
                    lastResolvedAt = null,
                    lastResolveStatus = DomainResolutionStatus.NEVER,
                )
            )
        }
        coordinator.refreshAndRebuild(tunnelId, "rule_import")
    }

    fun dismissDiagnosis() = _state.update { it.copy(diagnosis = null) }

    /** Replaces local rules with an independent copy of the current global rules. */
    fun copyGlobal() = runAction {
        val globalId = tunnelRepository.globalTunnelFlow.first()?.id
            ?: throw IllegalStateException("Global tunnel is unavailable")
        if (globalId == tunnelId) return@runAction

        val snapshot = repository.forTunnel(globalId)
        repository.deleteForTunnel(tunnelId)
        snapshot.forEach { rule ->
            repository.upsert(
                rule.copy(
                    id = UUID.randomUUID().toString(),
                    tunnelId = tunnelId,
                    source = DomainRuleSource.SHARE,
                    // DNS observations are scope/network dependent and must be rebuilt locally.
                    resolvedIpv4 = emptyList(),
                    resolvedIpv6 = emptyList(),
                    lastResolvedAt = null,
                    lastResolveStatus = DomainResolutionStatus.NEVER,
                )
            )
        }
        coordinator.refreshAndRebuild(tunnelId, "global_snapshot_copy")
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun reportError(message: String) = _state.update { it.copy(error = message) }

    private fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, error = null) }
            runCatching { action() }
                .onSuccess { _state.update { it.copy(isWorking = false) } }
                .onFailure {
                    _state.update {
                        it.copy(
                            isWorking = false,
                            error = context.getString(R.string.domain_sites_action_error),
                        )
                    }
                }
        }
    }
}

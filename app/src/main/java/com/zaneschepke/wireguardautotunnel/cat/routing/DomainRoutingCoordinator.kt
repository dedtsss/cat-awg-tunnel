package com.zaneschepke.wireguardautotunnel.cat.routing

import com.dedtsss.catawg.core.diagnostics.ClientDiagnosticRecorder
import com.dedtsss.catawg.core.diagnostics.DiagnosticCategory
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import com.dedtsss.catawg.core.diagnostics.DiagnosticSeverity
import com.dedtsss.catawg.core.routing.DomainMatchMode
import com.dedtsss.catawg.core.routing.DomainNormalizer
import com.dedtsss.catawg.core.routing.DomainResolutionStatus
import com.dedtsss.catawg.core.routing.DomainResolver
import com.dedtsss.catawg.core.routing.DomainRouteRebuildDecision
import com.dedtsss.catawg.core.routing.DomainRouteTarget
import com.dedtsss.catawg.core.routing.DomainRoutingPlanner
import com.dedtsss.catawg.core.routing.DomainRule
import com.dedtsss.catawg.core.routing.DomainRuleRepository
import com.dedtsss.catawg.core.routing.DomainRuleSource
import com.zaneschepke.networkmonitor.NetworkMonitor
import com.zaneschepke.tunnel.backend.Backend
import com.zaneschepke.tunnel.event.TunnelEvent
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Coordinates immediate, connect-time and network-change DNS refreshes with the native VPN bounce.
 */
class DomainRoutingCoordinator(
    private val repository: DomainRuleRepository,
    private val tunnelRepository: TunnelRepository,
    private val resolver: DomainResolver,
    private val routeProvider: AndroidDomainRouteProvider,
    private val backend: Backend,
    private val diagnostics: ClientDiagnosticRecorder,
    networkMonitor: NetworkMonitor,
    applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    init {
        applicationScope.launch(ioDispatcher) {
            repository.rules
                .combine(tunnelRepository.globalTunnelFlow) { rules, global ->
                    rules to global?.id
                }
                .collect { (rules, globalId) ->
                    routeProvider.replaceAll(rules, globalId)
                    // Mutations go through the coordinator methods below, which capture the
                    // previous effective route set before writing and rebuild active tunnels.
                    // This collector only refreshes the synchronous Builder cache; doing the
                    // rebuild here as well would race those serialized mutations.
                }
        }
        applicationScope.launch(ioDispatcher) {
            networkMonitor.connectivityStateFlow
                .map { it.activeNetwork.key() }
                .distinctUntilChanged()
                .collect { key ->
                    backend.status.first().activeTunnels.keys.forEach { tunnelId ->
                        refreshAndRebuild(tunnelId, "network:$key")
                    }
                }
        }
        applicationScope.launch(ioDispatcher) {
            // Upstream's seamless recovery owns the first reconnect. Once it reports completion,
            // refresh domain DNS and perform one additional normal bounce only if exclusions
            // actually changed. This keeps the VPN lifecycle upstream-owned and avoids stale
            // direct-network answers after a recovery reconnect.
            backend.events.collect { event ->
                if (event is TunnelEvent.SeamlessRecoveryAttempted) {
                    refreshAndRebuild(event.tunnelId, "recovery_reconnect")
                }
            }
        }
    }

    suspend fun createAndApply(
        tunnelId: Int,
        rawDomain: String,
        matchMode: DomainMatchMode = DomainMatchMode.SUFFIX,
        routeTarget: DomainRouteTarget = DomainRouteTarget.LOCAL_DIRECT,
        source: DomainRuleSource = DomainRuleSource.MANUAL,
        comment: String? = null,
    ): DomainRule {
        val domain =
            DomainNormalizer.fromSharedText(rawDomain)
                ?: throw IllegalArgumentException("Invalid domain or URL")
        val rule =
            DomainRule(
                tunnelId = tunnelId,
                domain = domain,
                matchMode = matchMode,
                routeTarget = routeTarget,
                source = source,
                comment = comment?.takeIf { it.isNotBlank() },
            )
        val affected = affectedTunnelIds(tunnelId)
        val before = affected.associateWith { effectiveExclusions(it) }
        repository.upsert(rule)
        if (affected.isEmpty()) {
            refreshRules(tunnelId)
        } else {
            affected.forEach { refreshAndRebuild(it, "rule_added", before[it]) }
        }
        return repository.get(rule.id) ?: rule
    }

    suspend fun updateAndApply(rule: DomainRule): DomainRule {
        val normalized =
            DomainNormalizer.normalize(rule.domain)
                ?: throw IllegalArgumentException("Invalid domain")
        val affected = affectedTunnelIds(rule.tunnelId)
        val before = affected.associateWith { effectiveExclusions(it) }
        val normalizedRule = rule.copy(domain = normalized)
        repository.upsert(normalizedRule)
        if (affected.isEmpty()) {
            refreshRules(normalizedRule.tunnelId)
        } else {
            affected.forEach { refreshAndRebuild(it, "rule_updated", before[it]) }
        }
        return repository.get(normalizedRule.id) ?: normalizedRule
    }

    suspend fun deleteAndApply(rule: DomainRule) {
        val affected = affectedTunnelIds(rule.tunnelId)
        val before = affected.associateWith { effectiveExclusions(it) }
        repository.delete(rule.id)
        if (affected.isEmpty()) {
            refreshRules(rule.tunnelId)
        } else {
            affected.forEach {
                refreshAndRebuild(it, "rule_deleted", before[it])
            }
        }
    }

    /**
     * Called directly from the normal tunnel-start lifecycle before VpnService.Builder.establish().
     */
    suspend fun refreshForTunnel(tunnelId: Int): List<DomainRule> = refreshRules(tunnelId)

    suspend fun refreshAndRebuild(
        tunnelId: Int,
        reason: String,
        before: List<com.dedtsss.catawg.core.routing.RouteExclusion>? = null,
    ) {
        val affected = affectedTunnelIds(tunnelId)
        if (affected.isEmpty()) {
            refreshRules(tunnelId)
            return
        }
        affected.forEach { id ->
            val initial = before ?: effectiveExclusions(id)
            refreshRules(id)
            rebuildIfRouteSetChanged(id, initial, reason)
        }
    }

    private suspend fun refreshRules(tunnelId: Int): List<DomainRule> {
        val globalId = tunnelRepository.globalTunnelFlow.first()?.id
        val scopes = listOfNotNull(globalId.takeIf { it != tunnelId }, tunnelId).distinct()
        val refreshedByScope = scopes.associateWith { scopeId ->
            repository.forTunnel(scopeId).map { rule ->
                if (!rule.enabled) return@map rule
                val resolution = resolver.resolve(rule.domain)
                val merged = DomainRoutingPlanner.mergeResolution(rule, resolution)
                repository.upsert(merged)
                diagnostics.record(
                    DiagnosticEvent(
                        category = DiagnosticCategory.DNS,
                        severity =
                            if (resolution.status == DomainResolutionStatus.SUCCESS)
                                DiagnosticSeverity.INFO
                            else DiagnosticSeverity.WARNING,
                        code =
                            if (resolution.status == DomainResolutionStatus.SUCCESS) "DNS_RESOLVED"
                            else "DNS_RESOLUTION_FAILED",
                        summary = "Resolved ${rule.domain}: ${resolution.status}",
                        details =
                            mapOf(
                                "domain" to rule.domain,
                                "ipv4Count" to resolution.ipv4.size.toString(),
                                "ipv6Count" to resolution.ipv6.size.toString(),
                                "status" to resolution.status.name,
                            ),
                        tunnelId = scopeId.toString(),
                    )
                )
                merged
            }
        }
        val globalRules = globalId?.let { refreshedByScope[it] ?: repository.forTunnel(it) }.orEmpty()
        val localRules = refreshedByScope[tunnelId] ?: repository.forTunnel(tunnelId)
        if (globalId == tunnelId) {
            routeProvider.updateGlobal(tunnelId, localRules)
            return localRules
        }
        val effective = DomainRoutingPlanner.effectiveRules(globalRules, localRules)
        routeProvider.update(tunnelId, localRules)
        return effective
    }

    private suspend fun affectedTunnelIds(scopeId: Int): List<Int> {
        val globalId = tunnelRepository.globalTunnelFlow.first()?.id
        return if (scopeId == globalId) {
            backend.status.first().activeTunnels.keys.toList()
        } else {
            listOf(scopeId)
        }
    }

    private suspend fun effectiveExclusions(tunnelId: Int) =
        DomainRoutingPlanner.exclusions(effectiveRules(tunnelId))

    private suspend fun effectiveRules(tunnelId: Int): List<DomainRule> {
        val globalId = tunnelRepository.globalTunnelFlow.first()?.id
        val local = repository.forTunnel(tunnelId)
        if (globalId == null || globalId == tunnelId) return local
        return DomainRoutingPlanner.effectiveRules(repository.forTunnel(globalId), local)
    }

    private suspend fun rebuildIfRouteSetChanged(
        tunnelId: Int,
        before: List<com.dedtsss.catawg.core.routing.RouteExclusion>,
        reason: String,
    ) {
        val after = routeProvider.exclusionsFor(tunnelId)
        if (
            !DomainRouteRebuildDecision.requiresVpnRebuild(
                before = before,
                after = after,
                tunnelIsActive = tunnelId in backend.status.first().activeTunnels,
            )
        )
            return
        val bounced = backend.bounceTunnelDevice(tunnelId, withFreshResolution = false)
        diagnostics.record(
            DiagnosticEvent(
                category = DiagnosticCategory.ROUTE,
                severity = if (bounced) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR,
                code = if (bounced) "TUNNEL_BOUNCED_FOR_DOMAIN_ROUTES" else "TUNNEL_BOUNCE_FAILED",
                summary =
                    "Domain route update $reason ${if (bounced) "applied" else "failed to apply"}",
                details = mapOf("reason" to reason, "exclusionCount" to after.size.toString()),
                tunnelId = tunnelId.toString(),
            )
        )
    }
}

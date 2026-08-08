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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Coordinates immediate, connect-time and network-change DNS refreshes with the native VPN bounce.
 */
class DomainRoutingCoordinator(
    private val repository: DomainRuleRepository,
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
            repository.rules.collect(routeProvider::replaceAll)
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
        repository.upsert(rule)
        refreshAndRebuild(tunnelId, "rule_added")
        return repository.get(rule.id) ?: rule
    }

    suspend fun updateAndApply(rule: DomainRule): DomainRule {
        val normalized =
            DomainNormalizer.normalize(rule.domain)
                ?: throw IllegalArgumentException("Invalid domain")
        val before = DomainRoutingPlanner.exclusions(repository.forTunnel(rule.tunnelId))
        val normalizedRule = rule.copy(domain = normalized)
        repository.upsert(normalizedRule)
        refreshAndRebuild(normalizedRule.tunnelId, "rule_updated", before)
        return repository.get(normalizedRule.id) ?: normalizedRule
    }

    suspend fun deleteAndApply(rule: DomainRule) {
        val before = DomainRoutingPlanner.exclusions(repository.forTunnel(rule.tunnelId))
        repository.delete(rule.id)
        routeProvider.update(rule.tunnelId, repository.forTunnel(rule.tunnelId))
        rebuildIfRouteSetChanged(rule.tunnelId, before, "rule_deleted")
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
        val initial = before ?: DomainRoutingPlanner.exclusions(repository.forTunnel(tunnelId))
        refreshRules(tunnelId)
        rebuildIfRouteSetChanged(tunnelId, initial, reason)
    }

    private suspend fun refreshRules(tunnelId: Int): List<DomainRule> {
        val refreshed =
            repository.forTunnel(tunnelId).map { rule ->
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
                        tunnelId = tunnelId.toString(),
                    )
                )
                merged
            }
        routeProvider.update(tunnelId, refreshed)
        return refreshed
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

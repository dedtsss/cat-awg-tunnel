package com.zaneschepke.wireguardautotunnel.cat.routing

import com.dedtsss.catawg.core.routing.DomainRouteProvider
import com.dedtsss.catawg.core.routing.DomainRoutingPlanner
import com.dedtsss.catawg.core.routing.DomainRule
import com.dedtsss.catawg.core.routing.RouteExclusion
import java.util.concurrent.ConcurrentHashMap

/** Snapshot cache keeps VpnService.Builder free of Room I/O. */
class AndroidDomainRouteProvider : DomainRouteProvider {
    private val byTunnel = ConcurrentHashMap<Int, List<RouteExclusion>>()
    private val localRulesByTunnel = ConcurrentHashMap<Int, List<DomainRule>>()
    @Volatile private var globalRules: List<DomainRule> = emptyList()
    @Volatile private var globalTunnelId: Int? = null

    @Synchronized
    override fun exclusionsFor(tunnelId: Int): List<RouteExclusion> = byTunnel[tunnelId].orEmpty()

    @Synchronized
    fun update(tunnelId: Int, rules: List<DomainRule>) {
        localRulesByTunnel[tunnelId] = rules
        updateEffective(tunnelId, DomainRoutingPlanner.effectiveRules(globalRules, rules))
    }

    @Synchronized
    fun updateEffective(tunnelId: Int, rules: List<DomainRule>) {
        byTunnel[tunnelId] = DomainRoutingPlanner.exclusions(rules)
    }

    @Synchronized
    fun updateGlobal(tunnelId: Int, rules: List<DomainRule>) {
        globalTunnelId = tunnelId
        globalRules = rules
        byTunnel[tunnelId] = DomainRoutingPlanner.exclusions(rules)
        localRulesByTunnel.forEach { (localTunnelId, localRules) ->
            updateEffective(
                localTunnelId,
                DomainRoutingPlanner.effectiveRules(rules, localRules),
            )
        }
    }

    @Synchronized
    fun replaceAll(rules: List<DomainRule>, globalTunnelId: Int? = this.globalTunnelId) {
        this.globalTunnelId = globalTunnelId
        this.globalRules = rules.filter { it.tunnelId == globalTunnelId }
        val grouped = rules.filter { it.tunnelId != globalTunnelId }.groupBy { it.tunnelId }
        localRulesByTunnel.keys.filterNot(grouped::containsKey).forEach(localRulesByTunnel::remove)
        grouped.forEach { (tunnelId, entries) -> localRulesByTunnel[tunnelId] = entries }
        val retainedTunnels = localRulesByTunnel.keys.toMutableSet().apply {
            globalTunnelId?.let { add(it) }
        }
        byTunnel.keys.filterNot(retainedTunnels::contains).forEach(byTunnel::remove)
        globalTunnelId?.let { byTunnel[it] = DomainRoutingPlanner.exclusions(this.globalRules) }
        localRulesByTunnel.forEach { (tunnelId, localRules) ->
            updateEffective(
                tunnelId,
                DomainRoutingPlanner.effectiveRules(this.globalRules, localRules),
            )
        }
    }
}

package com.zaneschepke.wireguardautotunnel.cat.routing

import com.dedtsss.catawg.core.routing.DomainRouteProvider
import com.dedtsss.catawg.core.routing.DomainRoutingPlanner
import com.dedtsss.catawg.core.routing.DomainRule
import com.dedtsss.catawg.core.routing.RouteExclusion
import java.util.concurrent.ConcurrentHashMap

/** Snapshot cache keeps VpnService.Builder free of Room I/O. */
class AndroidDomainRouteProvider : DomainRouteProvider {
    private val byTunnel = ConcurrentHashMap<Int, List<RouteExclusion>>()

    override fun exclusionsFor(tunnelId: Int): List<RouteExclusion> = byTunnel[tunnelId].orEmpty()

    fun update(tunnelId: Int, rules: List<DomainRule>) {
        byTunnel[tunnelId] = DomainRoutingPlanner.exclusions(rules)
    }

    fun replaceAll(rules: List<DomainRule>) {
        val grouped = rules.groupBy { it.tunnelId }
        byTunnel.keys.filterNot(grouped::containsKey).forEach(byTunnel::remove)
        grouped.forEach { (tunnelId, entries) -> update(tunnelId, entries) }
    }
}

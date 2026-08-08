package com.zaneschepke.wireguardautotunnel.cat.diagnostics

import com.dedtsss.catawg.core.diagnostics.ClientDiagnosticRecorder
import com.dedtsss.catawg.core.diagnostics.DiagnosticCategory
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import com.dedtsss.catawg.core.diagnostics.DiagnosticSeverity
import com.zaneschepke.networkmonitor.NetworkMonitor
import com.zaneschepke.tunnel.backend.Backend
import com.zaneschepke.tunnel.event.TunnelEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Bridges existing upstream tunnel/network signals into bounded structured Cat diagnostics. */
class ClientDiagnosticsObserver(
    private val backend: Backend,
    private val networkMonitor: NetworkMonitor,
    private val recorder: ClientDiagnosticRecorder,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            backend.events.collect { event -> recorder.record(event.toDiagnosticEvent()) }
        }
        scope.launch(ioDispatcher) {
            backend.status
                .map { it.activeTunnels.keys.sorted() }
                .distinctUntilChanged()
                .collect { active ->
                    recorder.record(
                        DiagnosticEvent(
                            category = DiagnosticCategory.TUNNEL,
                            code = if (active.isEmpty()) "VPN_SERVICE_STOPPED" else "VPN_SERVICE_ACTIVE",
                            summary =
                                if (active.isEmpty()) "No active tunnel reported by backend"
                                else "Backend reports ${active.size} active tunnel(s)",
                            details = mapOf("activeTunnelIds" to active.joinToString(",")),
                        )
                    )
                }
        }
        scope.launch(ioDispatcher) {
            networkMonitor.connectivityStateFlow
                .map { state -> state.activeNetwork.key() to state.hasUsableNetwork() }
                .distinctUntilChanged()
                .collect { (networkKey, usable) ->
                    recorder.record(
                        DiagnosticEvent(
                            category = DiagnosticCategory.NETWORK,
                            severity = if (usable) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                            code = if (usable) "NETWORK_AVAILABLE" else "NETWORK_LOST",
                            summary = "Underlying network ${if (usable) "available" else "unavailable"}: $networkKey",
                            details = mapOf("network" to networkKey),
                        )
                    )
                }
        }
    }

    private fun TunnelEvent.toDiagnosticEvent(): DiagnosticEvent =
        when (this) {
            is TunnelEvent.DynamicDnsUpdate ->
                DiagnosticEvent(
                    category = DiagnosticCategory.DNS,
                    code = "TUNNEL_DDNS_UPDATED",
                    summary = "Tunnel endpoint DNS changed.",
                    details = mapOf("changedPeerCount" to changedPeers.size.toString()),
                    tunnelId = tunnelId,
                )

            is TunnelEvent.FallbackToIpv4 ->
                DiagnosticEvent(
                    category = DiagnosticCategory.ROUTE,
                    severity = DiagnosticSeverity.WARNING,
                    code = "IP_FAMILY_MISMATCH",
                    summary = "Tunnel fell back to IPv4.",
                    tunnelId = tunnelId,
                )

            is TunnelEvent.RecoveredToIpv6 ->
                DiagnosticEvent(
                    category = DiagnosticCategory.ROUTE,
                    code = "IPV6_RECOVERED",
                    summary = "Tunnel recovered IPv6 connectivity.",
                    tunnelId = tunnelId,
                )

            is TunnelEvent.NoRootShellAccess ->
                DiagnosticEvent(
                    category = DiagnosticCategory.SYSTEM,
                    severity = DiagnosticSeverity.WARNING,
                    code = "ROOT_SHELL_UNAVAILABLE",
                    summary = "An optional root shell action was unavailable.",
                    tunnelId = tunnelId,
                )

            is TunnelEvent.SeamlessRecoveryAttempted ->
                DiagnosticEvent(
                    category = DiagnosticCategory.TUNNEL,
                    severity = DiagnosticSeverity.WARNING,
                    code = "TUNNEL_BOUNCE_ATTEMPTED",
                    summary = "Backend started a seamless tunnel recovery attempt.",
                    tunnelId = tunnelId,
                )
        }
}

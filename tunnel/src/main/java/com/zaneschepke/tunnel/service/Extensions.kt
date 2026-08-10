package com.zaneschepke.tunnel.service

import com.zaneschepke.tunnel.model.BackendMode
import com.zaneschepke.tunnel.state.BackendStatus

fun BackendStatus.toNotificationComparisonKey(): Any =
    activeTunnels.mapValues { (_, tunnel) ->
        Quintuple(
            tunnel.transportState,
            tunnel.bootstrapState,
            tunnel.mode is BackendMode.Vpn || tunnel.mode is BackendMode.Proxy.KillSwitchPrimary,
            tunnel.trafficRate,
            tunnel.connectionQuality,
        )
    } to (activeTunnels.keys to (killSwitch.enabled to dnsMode))

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)

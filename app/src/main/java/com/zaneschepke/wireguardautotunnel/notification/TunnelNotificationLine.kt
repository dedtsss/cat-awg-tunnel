package com.zaneschepke.wireguardautotunnel.notification

import com.zaneschepke.wireguardautotunnel.ui.state.DisplayTunnelState
import com.zaneschepke.tunnel.state.TunnelConnectionQuality
import com.zaneschepke.tunnel.state.TunnelTrafficRate

data class TunnelNotificationLine(
    val id: Int,
    val name: String,
    val displayState: DisplayTunnelState,
    val connectionQuality: TunnelConnectionQuality,
    val trafficRate: TunnelTrafficRate,
)

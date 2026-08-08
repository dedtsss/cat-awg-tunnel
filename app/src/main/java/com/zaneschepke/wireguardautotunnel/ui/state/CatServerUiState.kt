package com.zaneschepke.wireguardautotunnel.ui.state

import com.dedtsss.catawg.core.protocol.CatHealth
import com.dedtsss.catawg.core.protocol.ServerCapabilities
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettings

enum class CatServerConnectionStatus {
    NOT_CONFIGURED,
    CONFIGURED,
    CONNECTED,
    ERROR,
}

data class CatServerUiState(
    val settings: CatServerSettings = CatServerSettings(),
    val status: CatServerConnectionStatus = CatServerConnectionStatus.NOT_CONFIGURED,
    val health: CatHealth? = null,
    val capabilities: ServerCapabilities? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val lastAction: String? = null,
)

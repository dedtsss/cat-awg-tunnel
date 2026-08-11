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

/** The guided flow deliberately exposes only non-secret pairing metadata. */
data class CatPairingPreview(
    val server: String,
    val fingerprintReceived: Boolean = true,
)

enum class CatPairingStep {
    START,
    VERIFY,
    PAIR,
    CONNECTED,
}

enum class CatServerAction {
    PAIRING_IMPORTED,
    SERVER_VERIFIED,
    PAIRED,
    REFRESHED,
    FORGOTTEN,
}

data class CatServerUiState(
    val settings: CatServerSettings = CatServerSettings(),
    val status: CatServerConnectionStatus = CatServerConnectionStatus.NOT_CONFIGURED,
    val health: CatHealth? = null,
    val capabilities: ServerCapabilities? = null,
    val busy: Boolean = false,
    val errorCode: String? = null,
    val lastAction: CatServerAction? = null,
    val pairingStep: CatPairingStep = CatPairingStep.START,
    val pairingPreview: CatPairingPreview? = null,
)

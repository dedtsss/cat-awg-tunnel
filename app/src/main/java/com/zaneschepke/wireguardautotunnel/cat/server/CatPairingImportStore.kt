package com.zaneschepke.wireguardautotunnel.cat.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Short-lived hand-off for a scanned, shared or deep-linked pairing envelope.
 *
 * The payload is deliberately process-memory only: it is never written to preferences, logs or
 * saved Compose state because it carries a one-time bootstrap token.
 */
class CatPairingImportStore {
    private val _pendingPayload = MutableStateFlow<String?>(null)
    val pendingPayload = _pendingPayload.asStateFlow()

    fun accept(rawPayload: String) {
        _pendingPayload.value = rawPayload
    }

    fun consume(rawPayload: String) {
        if (_pendingPayload.value == rawPayload) _pendingPayload.value = null
    }
}

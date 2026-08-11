package com.zaneschepke.tunnel.state

import com.zaneschepke.tunnel.Tunnel

/**
 * A deliberately conservative connection assessment. Transfer volume is not an input: an idle
 * tunnel is not unhealthy merely because it has no traffic.
 */
enum class TunnelConnectionQuality {
    CONNECTING,
    STABLE,
    UNSTABLE,
    NO_CONNECTION,
}

data class ConnectionQualitySample(
    val transportState: Tunnel.State,
    val stateChangedAtMillis: Long,
    val latestHandshakeEpochMillis: Long? = null,
    val lastRecoveryAttemptMillis: Long = 0L,
    val nowMillis: Long,
)

object ConnectionQualityEvaluator {
    private const val HANDSHAKE_STALE_AFTER_MILLIS = 3 * 60 * 1_000L
    private const val RECOVERY_WINDOW_MILLIS = 2 * 60 * 1_000L
    private const val FAILURE_GRACE_MILLIS = 30 * 1_000L

    fun evaluate(sample: ConnectionQualitySample): TunnelConnectionQuality {
        return when (sample.transportState) {
            Tunnel.State.Down,
            Tunnel.State.Stopping -> TunnelConnectionQuality.NO_CONNECTION

            Tunnel.State.Starting -> TunnelConnectionQuality.CONNECTING

            Tunnel.State.Up.HandshakeFailure -> {
                if (elapsed(sample.nowMillis, sample.stateChangedAtMillis) >= FAILURE_GRACE_MILLIS) {
                    TunnelConnectionQuality.NO_CONNECTION
                } else {
                    TunnelConnectionQuality.UNSTABLE
                }
            }

            Tunnel.State.Up.Healthy -> {
                val hasRecentRecovery =
                    sample.lastRecoveryAttemptMillis > 0L &&
                        elapsed(sample.nowMillis, sample.lastRecoveryAttemptMillis) <
                            RECOVERY_WINDOW_MILLIS
                val hasStaleHandshake =
                    sample.latestHandshakeEpochMillis?.let { handshake ->
                        elapsed(sample.nowMillis, handshake) > HANDSHAKE_STALE_AFTER_MILLIS
                    } == true
                val hasNoHandshakeEvidence = sample.latestHandshakeEpochMillis == null

                if (hasRecentRecovery || hasStaleHandshake || hasNoHandshakeEvidence) {
                    TunnelConnectionQuality.UNSTABLE
                } else {
                    TunnelConnectionQuality.STABLE
                }
            }
        }
    }

    private fun elapsed(nowMillis: Long, thenMillis: Long): Long =
        (nowMillis - thenMillis).coerceAtLeast(0L)
}

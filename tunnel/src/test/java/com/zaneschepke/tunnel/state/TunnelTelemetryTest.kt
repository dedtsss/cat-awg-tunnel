package com.zaneschepke.tunnel.state

import com.zaneschepke.tunnel.Tunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelTelemetryTest {
    @Test
    fun `traffic rate uses counter deltas and resets safely when counters restart`() {
        val tracker = TunnelTrafficRateTracker()

        assertEquals(TunnelTrafficRate(), tracker.update(TunnelTrafficCounters(100, 100), 1_000))
        assertEquals(
            TunnelTrafficRate(downloadBytesPerSecond = 500, uploadBytesPerSecond = 250),
            tracker.update(TunnelTrafficCounters(1_100, 600), 3_000),
        )
        assertEquals(
            TunnelTrafficRate(),
            tracker.update(TunnelTrafficCounters(10, 10), 6_000),
        )
    }

    @Test
    fun `idle healthy tunnel remains stable because quality does not use transfer speed`() {
        val quality =
            ConnectionQualityEvaluator.evaluate(
                ConnectionQualitySample(
                    transportState = Tunnel.State.Up.Healthy,
                    stateChangedAtMillis = 1_000,
                    latestHandshakeEpochMillis = 9_000,
                    nowMillis = 10_000,
                )
            )

        assertEquals(TunnelConnectionQuality.STABLE, quality)
        assertTrue(TunnelTrafficRate().compactDisplay().contains("↓ 0 bit/s"))
    }

    @Test
    fun `old handshake is unstable but explicit sustained handshake failure is no connection`() {
        val stale =
            ConnectionQualityEvaluator.evaluate(
                ConnectionQualitySample(
                    transportState = Tunnel.State.Up.Healthy,
                    stateChangedAtMillis = 1_000,
                    latestHandshakeEpochMillis = 1_000,
                    nowMillis = 190_001,
                )
            )
        val failed =
            ConnectionQualityEvaluator.evaluate(
                ConnectionQualitySample(
                    transportState = Tunnel.State.Up.HandshakeFailure,
                    stateChangedAtMillis = 1_000,
                    nowMillis = 31_001,
                )
            )

        assertEquals(TunnelConnectionQuality.UNSTABLE, stale)
        assertEquals(TunnelConnectionQuality.NO_CONNECTION, failed)
    }
}

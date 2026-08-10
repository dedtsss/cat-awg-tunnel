package com.zaneschepke.tunnel.state

import java.util.Locale

data class TunnelTrafficCounters(val receivedBytes: Long, val sentBytes: Long)

data class TunnelTrafficRate(
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
) {
    fun compactDisplay(): String =
        "↓ ${formatBits(downloadBytesPerSecond)} · ↑ ${formatBits(uploadBytesPerSecond)}"

    private fun formatBits(bytesPerSecond: Long): String {
        val bits = bytesPerSecond.coerceAtLeast(0L).toDouble() * 8.0
        return when {
            bits < 1_000.0 -> "${bits.toLong()} bit/s"
            bits < 1_000_000.0 -> String.format(Locale.ROOT, "%.1f kbit/s", bits / 1_000.0)
            else -> String.format(Locale.ROOT, "%.1f Mbit/s", bits / 1_000_000.0)
        }
    }
}

/** Converts cumulative interface counters into a low-overhead instantaneous rate. */
class TunnelTrafficRateTracker {
    private var previous: Sample? = null
    private var lastRate = TunnelTrafficRate()

    fun update(counters: TunnelTrafficCounters, sampledAtMillis: Long): TunnelTrafficRate {
        val current =
            Sample(
                receivedBytes = counters.receivedBytes.coerceAtLeast(0L),
                sentBytes = counters.sentBytes.coerceAtLeast(0L),
                sampledAtMillis = sampledAtMillis,
            )
        val before = previous
        previous = current

        if (before == null) {
            lastRate = TunnelTrafficRate()
            return lastRate
        }

        val elapsedMillis = current.sampledAtMillis - before.sampledAtMillis
        val receivedDelta = current.receivedBytes - before.receivedBytes
        val sentDelta = current.sentBytes - before.sentBytes

        // A backend restart, interface recreation or a non-monotonic clock starts a fresh baseline
        // rather than showing a negative or implausibly high transfer rate.
        if (elapsedMillis <= 0L || receivedDelta < 0L || sentDelta < 0L) {
            lastRate = TunnelTrafficRate()
            return lastRate
        }

        lastRate =
            TunnelTrafficRate(
                downloadBytesPerSecond = receivedDelta * 1_000L / elapsedMillis,
                uploadBytesPerSecond = sentDelta * 1_000L / elapsedMillis,
            )
        return lastRate
    }

    private data class Sample(
        val receivedBytes: Long,
        val sentBytes: Long,
        val sampledAtMillis: Long,
    )
}

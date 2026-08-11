package com.zaneschepke.tunnel.state

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

data class TunnelTrafficCounters(val receivedBytes: Long, val sentBytes: Long)

data class TunnelTrafficRate(
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
) {
    fun compactDisplay(): String =
        "↓${compactRate(downloadBytesPerSecond).display()} · ↑${compactRate(uploadBytesPerSecond).display()}"

    fun compactRate(bytesPerSecond: Long, locale: Locale = Locale.ROOT): CompactTrafficRate {
        val bits = bytesPerSecond.coerceAtLeast(0L).toDouble() * 8.0
        return when {
            bits < 1_000.0 -> CompactTrafficRate(bits.toLong().toString(), TrafficRateUnit.BIT)
            bits < 1_000_000.0 -> compact(bits / 1_000.0, TrafficRateUnit.KBIT, locale)
            bits < 1_000_000_000.0 -> compact(bits / 1_000_000.0, TrafficRateUnit.MBIT, locale)
            else -> compact(bits / 1_000_000_000.0, TrafficRateUnit.GBIT, locale)
        }
    }

    private fun compact(value: Double, unit: TrafficRateUnit, locale: Locale): CompactTrafficRate =
        CompactTrafficRate(
            amount = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).format(value),
            unit = unit,
        )
}

enum class TrafficRateUnit(val englishSuffix: String) {
    BIT("bit/s"),
    KBIT("kbit/s"),
    MBIT("Mbit/s"),
    GBIT("Gbit/s"),
}

data class CompactTrafficRate(val amount: String, val unit: TrafficRateUnit) {
    fun display(): String = "$amount ${unit.englishSuffix}"
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

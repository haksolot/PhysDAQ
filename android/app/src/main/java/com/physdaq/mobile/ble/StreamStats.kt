package com.physdaq.mobile.ble

import kotlin.math.sqrt

/**
 * Rolling statistics on how samples actually arrive over BLE.
 *
 * The firmware sends one line per 40 ms (~25 Hz) but carries no clock, so the
 * only time base the app has is arrival time. BLE delivers notifications in
 * bursts per connection event (15-30 ms apart), so arrival times can be
 * bunched even when the device sampled evenly. This class measures how much,
 * over the last [windowSize] intervals (10 s at 25 Hz).
 *
 *  - rateHz:   1000 / mean interval
 *  - stdMs:    spread of the intervals (0 for a perfectly regular stream)
 *  - maxMs:    longest gap; well above 40 ms means samples were missed
 *  - burstPct: share of intervals under [BURST_MS], i.e. samples that arrived
 *              together with the one before them
 *
 * Intervals over [MAX_INTERVAL_MS] are treated as a reconnect, not jitter, and
 * are left out of the window.
 *
 * Safe to call [record] from one thread and [snapshot] from another.
 */
class StreamStats(private val windowSize: Int = 250) {

    data class Snapshot(
        val samples: Long,
        val rateHz: Double,
        val meanMs: Double,
        val stdMs: Double,
        val maxMs: Double,
        val burstPct: Double
    )

    private val ring = DoubleArray(windowSize)
    private var filled = 0
    private var next = 0
    private var last = -1L
    private var total = 0L

    @Synchronized
    fun record(nanos: Long) {
        total++
        if (last >= 0) {
            val ms = (nanos - last) / 1_000_000.0
            if (ms in 0.0..MAX_INTERVAL_MS) {
                ring[next] = ms
                next = (next + 1) % windowSize
                if (filled < windowSize) filled++
            }
        }
        last = nanos
    }

    @Synchronized
    fun snapshot(): Snapshot {
        if (filled == 0) return Snapshot(total, 0.0, 0.0, 0.0, 0.0, 0.0)

        var sum = 0.0
        var max = 0.0
        var bursts = 0
        for (i in 0 until filled) {
            val v = ring[i]
            sum += v
            if (v > max) max = v
            if (v < BURST_MS) bursts++
        }
        val mean = sum / filled
        var sq = 0.0
        for (i in 0 until filled) {
            val d = ring[i] - mean
            sq += d * d
        }
        return Snapshot(
            samples = total,
            rateHz = if (mean > 0) 1000.0 / mean else 0.0,
            meanMs = mean,
            stdMs = sqrt(sq / filled),
            maxMs = max,
            burstPct = 100.0 * bursts / filled
        )
    }

    companion object {
        const val MAX_INTERVAL_MS = 2000.0
        const val BURST_MS = 10.0
    }
}

package com.physdaq.mobile.library

import kotlin.math.floor

/**
 * Lines up several nodes' once-per-second heart-rate traces on one time axis
 * and measures how well they agree.
 *
 * Every file in a session counts time from the same session start, so a trace
 * that starts at 0.19 s and one that starts at 0.17 s both start in second 0;
 * [RecordingAnalysis.startSec] gives each trace's offset. Pure Kotlin.
 */
object SessionComparison {

    /** Traces on a shared axis: index i of every series is second ([startSec] + i) of the session. */
    class Aligned(val startSec: Int, val series: List<FloatArray>) {
        val length: Int get() = series.firstOrNull()?.size ?: 0
    }

    /** [traces]: (first sample's time in s, one value per second; NaN = no reading). */
    fun align(traces: List<Pair<Double, FloatArray>>): Aligned {
        if (traces.isEmpty()) return Aligned(0, emptyList())
        val starts = traces.map { floor(it.first).toInt() }
        val start = starts.min()
        val end = traces.indices.maxOf { starts[it] + traces[it].second.size }
        val out = traces.indices.map { k ->
            val arr = FloatArray(end - start) { Float.NaN }
            traces[k].second.copyInto(arr, destinationOffset = starts[k] - start)
            arr
        }
        return Aligned(start, out)
    }

    /**
     * @property secondsCompared seconds where at least two nodes had a reading
     * @property medianSpread median of (highest - lowest) BPM across nodes in those seconds
     * @property maxSpread the largest such difference
     * @property means each series' mean BPM (null if it never had a reading)
     */
    data class Agreement(
        val secondsCompared: Int,
        val medianSpread: Double?,
        val maxSpread: Double?,
        val means: List<Double?>
    )

    fun agreement(series: List<FloatArray>): Agreement {
        val n = series.firstOrNull()?.size ?: 0
        val spreads = ArrayList<Double>()
        for (i in 0 until n) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var count = 0
            for (s in series) {
                val v = s[i]
                if (v.isNaN()) continue
                count++
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (count >= 2) spreads.add((hi - lo).toDouble())
        }
        spreads.sort()
        val median = when {
            spreads.isEmpty() -> null
            spreads.size % 2 == 1 -> spreads[spreads.size / 2]
            else -> (spreads[spreads.size / 2 - 1] + spreads[spreads.size / 2]) / 2
        }
        val means = series.map { s ->
            val valid = s.filter { !it.isNaN() }
            if (valid.isEmpty()) null else valid.sumOf { it.toDouble() } / valid.size
        }
        return Agreement(spreads.size, median, spreads.lastOrNull(), means)
    }
}

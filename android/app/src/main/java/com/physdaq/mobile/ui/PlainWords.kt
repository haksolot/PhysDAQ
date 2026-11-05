package com.physdaq.mobile.ui

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Plain-language wording for the screens. The technical figures stay available
 * (in small print or under "More details"); these are the words shown first.
 * Pure Kotlin so the thresholds are unit tested.
 */
object PlainWords {

    /** Bluetooth signal strength from RSSI in dBm. */
    fun signal(rssiDbm: Int): String = when {
        rssiDbm >= -65 -> "Strong"
        rssiDbm >= -80 -> "OK"
        else -> "Weak"
    }

    enum class Quality(val word: String) { GOOD("Good"), FAIR("Fair"), POOR("Poor"), NONE("No data yet") }

    /**
     * How healthy a live stream is. Good: the normal ~24 readings/s with no pause
     * over 0.3 s. Fair: still usable for heart rate. Poor: dropping out.
     */
    fun connection(samples: Long, rateHz: Double, maxGapMs: Double): Quality = when {
        samples == 0L || rateHz <= 0.0 -> Quality.NONE
        rateHz >= 20.0 && maxGapMs <= 300.0 -> Quality.GOOD
        rateHz >= 15.0 && maxGapMs <= 1000.0 -> Quality.FAIR
        else -> Quality.POOR
    }

    /** "0.2 s" for a gap given in milliseconds. */
    fun seconds(ms: Double): String = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    /** "24 readings/s". */
    fun rate(hz: Double): String = "${hz.roundToInt()} readings/s"

    /** "worn the whole time" / "worn 83% of the time" / "never worn". */
    fun worn(pct: Double): String = when {
        pct >= 99.5 -> "worn the whole time"
        pct < 0.5 -> "never worn"
        else -> "worn ${pct.roundToInt()}% of the time"
    }

    /** Whole BPM for "usually agree within N BPM"; never says "within 0". */
    fun withinBpm(spread: Double): Int = spread.roundToInt().coerceAtLeast(1)
}

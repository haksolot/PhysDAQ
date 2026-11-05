package com.physdaq.mobile.dsp

import kotlin.math.exp
import kotlin.math.ln

/**
 * The live-trace filter: a high-pass (subtract a slow moving average, which
 * removes the DC level and drift) followed by a low-pass (smooths sensor noise).
 *
 * This is the bridge's `ppg_filt`, which at 100 Hz is
 *
 *     dc = dc*0.985 + x*0.015
 *     lp = lp*0.75  + (x - dc)*0.25
 *
 * Those constants are per-sample, so they only mean the right thing at 100 Hz.
 * Over BLE the stream is about 24 Hz, and copying them unchanged cuts the pulse
 * to roughly 68% of its size. So the filter is defined by the two time
 * constants the bridge's constants imply, and the per-sample constants are
 * recomputed for the rate actually measured. At 24.26 Hz the pulse keeps about
 * 92% of its amplitude, against 94% for the bridge at 100 Hz.
 *
 * This is for display only. Heart rate is computed by [SpectralBpm] and does
 * not use this filter.
 *
 * Not thread-safe.
 */
class PpgDisplayFilter(fs: Double = DEFAULT_FS) {

    private var aDc = alpha(TAU_DC, fs)
    private var aLp = alpha(TAU_LP, fs)
    private var dc = 0.0
    private var lp = 0.0
    private var primed = false

    /** Call occasionally with the measured sample rate; takes effect from the next sample. */
    fun setSampleRate(fs: Double) {
        if (fs > 0.0) {
            aDc = alpha(TAU_DC, fs)
            aLp = alpha(TAU_LP, fs)
        }
    }

    /** Forget the running averages, e.g. after a reconnect. */
    fun reset() {
        primed = false
        lp = 0.0
    }

    fun process(x: Double): Double {
        if (!primed) {
            // Start the slow average at the first value, so there is no giant
            // start-up swing from a zero-initialised average.
            dc = x
            lp = 0.0
            primed = true
        }
        dc += aDc * (x - dc)
        lp += aLp * ((x - dc) - lp)
        return lp
    }

    companion object {
        const val DEFAULT_FS = 24.3

        /** Time constants implied by the bridge's 0.015 and 0.25 at 100 Hz. */
        val TAU_DC: Double = -0.01 / ln(1.0 - 0.015)
        val TAU_LP: Double = -0.01 / ln(1.0 - 0.25)

        /** The per-sample smoothing constant for a first-order filter of time constant [tau] at [fs]. */
        fun alpha(tau: Double, fs: Double): Double = 1.0 - exp(-1.0 / (fs * tau))
    }
}

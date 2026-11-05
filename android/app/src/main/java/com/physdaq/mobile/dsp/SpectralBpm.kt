package com.physdaq.mobile.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Heart rate from one window of IR samples, by spectral peak.
 *
 * Steps: remove the straight-line trend (DC and slow drift), apply a Hann
 * window, compute the DFT only at the bins inside 0.7-3.0 Hz (42-180 BPM) plus
 * one bin either side, accept the strongest bin only if it is at least
 * [SNR_MIN] times the median of the band (the bridge's gate), then refine the
 * peak position by parabolic interpolation.
 *
 * Why there is no Butterworth filter here, unlike scripts/bridge.py: the band
 * limit and the detrend already do what it did for this purpose. On the real
 * 141 s hub recording the two methods agree to a median 0.01 BPM (largest
 * 0.16 BPM) on 123 windows, so the extra filter design and zero-phase
 * filtering buy nothing here.
 *
 * [fs] is the true sample rate of this window. Callers must measure it from the
 * timestamps; assuming 25 Hz when the stream runs at 24.26 Hz reads about 3%
 * high. (scripts/bridge.py assumes 100 Hz on every transport, which is wrong
 * over BLE.)
 *
 * Keep the arithmetic order identical to dsp_mirror.py: that file produces the
 * golden vectors the unit tests compare against.
 */
object SpectralBpm {

    const val LO_HZ = 0.7
    const val HI_HZ = 3.0
    const val SNR_MIN = 3.0

    /** Returns BPM, or null when there is no confident peak. */
    fun estimate(window: DoubleArray, fs: Double): Double? {
        val n = window.size
        if (n < 16 || !(fs > 0.0)) return null

        val x = detrend(window)
        for (i in 0 until n) {
            x[i] *= 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
        }

        val kLo = ceil(LO_HZ * n / fs).toInt()
        val kHi = floor(HI_HZ * n / fs).toInt()
        if (kLo < 1 || kHi - kLo < 2) return null

        // mags[0] is bin kLo-1, mags[1..bandSize] is the band, mags[bandSize+1] is bin kHi+1.
        val bandSize = kHi - kLo + 1
        val mags = DoubleArray(bandSize + 2) { magnitude(x, kLo - 1 + it) }

        var peakIdx = 1
        for (b in 1..bandSize) {
            if (mags[b] > mags[peakIdx]) peakIdx = b
        }
        val peak = mags[peakIdx]
        val median = median(mags.copyOfRange(1, bandSize + 1))
        if (peak < 1e-6 || peak < SNR_MIN * median) return null

        val y0 = mags[peakIdx - 1]
        val y1 = mags[peakIdx]
        val y2 = mags[peakIdx + 1]
        val denom = y0 - 2.0 * y1 + y2
        val delta = if (denom == 0.0) 0.0 else (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
        val k = kLo + (peakIdx - 1)
        return 60.0 * (k + delta) * fs / n
    }

    /** Subtracts the least-squares line. */
    private fun detrend(y: DoubleArray): DoubleArray {
        val n = y.size
        val mx = (n - 1) / 2.0
        var sum = 0.0
        for (v in y) sum += v
        val my = sum / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sxx += (i - mx) * (i - mx)
            sxy += (i - mx) * (y[i] - my)
        }
        val slope = sxy / sxx
        return DoubleArray(n) { y[it] - (my + slope * (it - mx)) }
    }

    private fun magnitude(x: DoubleArray, k: Int): Double {
        val n = x.size
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val a = 2.0 * PI * k * i / n
            re += x[i] * cos(a)
            im -= x[i] * sin(a)
        }
        return hypot(re, im)
    }

    private fun median(v: DoubleArray): Double {
        val s = v.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}

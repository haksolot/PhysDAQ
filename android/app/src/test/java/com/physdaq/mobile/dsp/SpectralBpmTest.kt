package com.physdaq.mobile.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectralBpmTest {

    /** A pulse-like signal: DC + a sine at [hz] + a small deterministic ripple, sampled at [fs]. */
    private fun signal(hz: Double, fs: Double, n: Int = 200): DoubleArray =
        DoubleArray(n) { i ->
            val t = i / fs
            28_000.0 + 150.0 * sin(2 * PI * hz * t) + 8.0 * sin(2 * PI * 5.3 * t + 1.0)
        }

    @Test
    fun findsA72BpmPulseAt25Hz() {
        val bpm = SpectralBpm.estimate(signal(hz = 1.2, fs = 25.0), fs = 25.0)
        assertNotNull(bpm)
        assertEquals(72.0, bpm!!, 0.7)
    }

    @Test
    fun findsA75BpmPulseAtTheMeasuredBleRate() {
        val bpm = SpectralBpm.estimate(signal(hz = 1.25, fs = 24.26), fs = 24.26)
        assertNotNull(bpm)
        assertEquals(75.0, bpm!!, 0.7)
    }

    @Test
    fun theReadingScalesWithTheSampleRateYouTellItAbout() {
        // The same samples read at 25 Hz must come out 25/24.26 higher than at
        // 24.26 Hz. This is why the rate has to be measured, not assumed.
        val samples = signal(hz = 1.25, fs = 24.26)
        val at2426 = SpectralBpm.estimate(samples, 24.26)!!
        val at25 = SpectralBpm.estimate(samples, 25.0)!!
        assertEquals(25.0 / 24.26, at25 / at2426, 1e-3)
    }

    @Test
    fun aConstantSignalHasNoHeartRate() {
        assertNull(SpectralBpm.estimate(DoubleArray(200) { 28_000.0 }, 25.0))
    }

    @Test
    fun allZerosHasNoHeartRate() {
        assertNull(SpectralBpm.estimate(DoubleArray(200), 25.0))
    }

    @Test
    fun aSlowDriftAloneIsNotMistakenForAPulse() {
        // A pure ramp is removed by the detrend step.
        assertNull(SpectralBpm.estimate(DoubleArray(200) { 20_000.0 + 40.0 * it }, 25.0))
    }

    @Test
    fun tooFewSamplesOrABadRateGiveNull() {
        assertNull(SpectralBpm.estimate(DoubleArray(5), 25.0))
        assertNull(SpectralBpm.estimate(signal(1.2, 25.0), 0.0))
    }
}

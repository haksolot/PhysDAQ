package com.physdaq.mobile.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PpgDisplayFilterTest {

    @Test
    fun atOneHundredHertzTheConstantsAreTheBridgesOwn() {
        assertEquals(0.015, PpgDisplayFilter.alpha(PpgDisplayFilter.TAU_DC, 100.0), 1e-12)
        assertEquals(0.25, PpgDisplayFilter.alpha(PpgDisplayFilter.TAU_LP, 100.0), 1e-12)
    }

    @Test
    fun aSlowerStreamGetsLargerPerSampleConstants() {
        // Same time constants, fewer samples per second, so each sample moves the average further.
        assertTrue(
            PpgDisplayFilter.alpha(PpgDisplayFilter.TAU_LP, 24.26) >
                PpgDisplayFilter.alpha(PpgDisplayFilter.TAU_LP, 100.0)
        )
    }

    @Test
    fun aConstantLevelComesOutAsZeroWithNoStartUpSwing() {
        val filter = PpgDisplayFilter(fs = 24.26)
        repeat(500) {
            assertEquals(0.0, filter.process(28_000.0), 1e-9)
        }
    }

    @Test
    fun aPulseAtOnePointTwoHertzKeepsAboutNinetyTwoPercentOfItsAmplitude() {
        val fs = 24.26
        val filter = PpgDisplayFilter(fs)
        val n = (30 * fs).toInt()
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until n) {
            val out = filter.process(28_000.0 + 100.0 * sin(2 * PI * 1.2 * i / fs))
            if (i >= n / 2) {                 // let the filter settle first
                if (out < lo) lo = out
                if (out > hi) hi = out
            }
        }
        val gain = (hi - lo) / 200.0
        assertTrue("gain was $gain", gain in 0.90..0.95)
    }

    @Test
    fun resetRestartsFromTheNextValueWithoutAJump() {
        val filter = PpgDisplayFilter(24.26)
        repeat(200) { filter.process(28_000.0) }
        filter.reset()
        // A new baseline far from the old one must not produce a huge first output.
        assertTrue(abs(filter.process(15_000.0)) < 1e-9)
    }
}

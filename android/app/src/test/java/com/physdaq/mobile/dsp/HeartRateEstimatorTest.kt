package com.physdaq.mobile.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HeartRateEstimatorTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun resourceLines(name: String): List<String> =
        checkNotNull(javaClass.classLoader).getResourceAsStream(name)!!
            .bufferedReader().use { it.readLines() }

    /** The synthetic pulse used in these tests: DC + [hz] sine + a small out-of-band ripple. */
    private fun ir(i: Int, hz: Double, dt: Double): Double {
        val t = i * dt
        return 28_000.0 + 150.0 * sin(2 * PI * hz * t) + 8.0 * sin(2 * PI * 5.3 * t + 1.0)
    }

    private fun nanos(seconds: Double): Long = Math.round(seconds * 1e9)

    private fun assertClose(label: String, expectedText: String, actual: Double?) {
        if (expectedText.isBlank()) {
            assertNull("$label should be null", actual)
        } else {
            assertNotNull("$label should not be null", actual)
            assertEquals(label, expectedText.toDouble(), actual!!, 1e-3)
        }
    }

    // ── agreement with the Python reference on real data ─────────────────────

    @Test
    fun matchesThePythonMirrorOnTheRealRecording() {
        // 60 s of the real hub recording (test1.csv), and the track the Python mirror
        // (dsp_mirror.py) produced for it. 50 estimates, one of them with no confident peak.
        val rows = resourceLines("test1_excerpt.csv").drop(1).map { it.split(",") }
        val golden = resourceLines("test1_golden_hr.csv").drop(1).map { it.split(",", limit = 3) }

        val estimator = HeartRateEstimator()
        val got = mutableListOf<Triple<Int, Double?, Double?>>()
        rows.forEachIndexed { i, f ->
            val r = estimator.update(nanos(f[0].toDouble()), f[1].toDouble())
            if (r.estimated) got += Triple(i, r.raw, r.bpm)
        }

        assertEquals("number of estimates", golden.size, got.size)
        golden.forEachIndexed { n, g ->
            val (index, raw, smoothed) = got[n]
            assertEquals("estimate $n: sample index", g[0].toInt(), index)
            assertClose("estimate $n: raw", g[1], raw)
            assertClose("estimate $n: smoothed", g[2], smoothed)
        }
    }

    // ── contact ──────────────────────────────────────────────────────────────

    @Test
    fun contactNeedsThirtySamplesAboveTheThreshold() {
        val estimator = HeartRateEstimator()
        val flags = (0 until 31).map { estimator.update(it * 40_000_000L, 20_000.0).contact }
        assertEquals(List(29) { false } + listOf(true, true), flags)
    }

    @Test
    fun anOpenAirSignalIsNeverContact() {
        val estimator = HeartRateEstimator()
        repeat(100) { assertFalse(estimator.update(it * 40_000_000L, 300.0).contact) }
    }

    @Test
    fun losingContactClearsTheReadingAndRestartsTheWindow() {
        val dt = 0.04122
        val estimator = HeartRateEstimator()
        var last = estimator.update(0, ir(0, 1.25, dt))
        for (i in 1 until 300) last = estimator.update(nanos(i * dt), ir(i, 1.25, dt))
        assertNotNull("has a reading before contact is lost", last.bpm)

        for (i in 300 until 360) last = estimator.update(nanos(i * dt), 300.0)
        assertFalse(last.contact)
        assertNull("reading is cleared with no contact", last.bpm)

        var samplesUntilEstimate = 0
        for (i in 360 until 760) {
            samplesUntilEstimate++
            if (estimator.update(nanos(i * dt), ir(i, 1.25, dt)).estimated) break
        }
        assertTrue(
            "needs a full window of contact again, took $samplesUntilEstimate samples",
            samplesUntilEstimate >= 200
        )
    }

    @Test
    fun resetForgetsEverything() {
        val dt = 0.04122
        val estimator = HeartRateEstimator()
        for (i in 0 until 300) estimator.update(nanos(i * dt), ir(i, 1.25, dt))
        estimator.reset()
        val r = estimator.update(0, ir(0, 1.25, dt))
        assertFalse(r.contact)
        assertNull(r.bpm)
    }

    // ── sample rate comes from the timestamps ────────────────────────────────

    @Test
    fun readsA75BpmPulseSampledAtTheRealBleRate() {
        val dt = 0.04122   // 24.26 Hz, what the real recording measured
        val estimator = HeartRateEstimator()
        var last: HeartRateEstimator.Result? = null
        for (i in 0 until 400) {
            val r = estimator.update(nanos(i * dt), ir(i, 1.25, dt))
            if (r.estimated) last = r
        }
        assertEquals(75.0, last!!.bpm!!, 1.0)
    }

    @Test
    fun theReadingFollowsTheTimestampsNotTheSampleCount() {
        // The same samples, but timestamps that claim a 40 ms spacing (25 Hz).
        // The estimator trusts the timestamps, so it reads about 3% high.
        val trueDt = 0.04122
        val estimator = HeartRateEstimator()
        var last: HeartRateEstimator.Result? = null
        for (i in 0 until 400) {
            val r = estimator.update(nanos(i * 0.040), ir(i, 1.25, trueDt))
            if (r.estimated) last = r
        }
        assertEquals(75.0 * 25.0 / 24.26, last!!.bpm!!, 1.0)
    }

    // ── smoothing ────────────────────────────────────────────────────────────

    @Test
    fun theReadingNeverMovesMoreThanFourBpmPerEstimate() {
        val dt = 0.04122
        val estimator = HeartRateEstimator()
        val readings = mutableListOf<Double?>()
        for (i in 0 until 600) {
            val hz = if (i < 300) 1.0 else 1.5      // 60 BPM, then an abrupt jump to 90 BPM
            val r = estimator.update(nanos(i * dt), ir(i, hz, dt))
            if (r.estimated) readings += r.bpm
        }
        readings.zipWithNext().forEach { (a, b) ->
            if (a != null && b != null) assertTrue("step ${abs(b - a)}", abs(b - a) <= 4.0 + 1e-6)
        }
    }
}

package com.physdaq.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatsTest {

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun emptyStreamReportsZeros() {
        val s = StreamStats().snapshot()
        assertEquals(0L, s.samples)
        assertEquals(0.0, s.rateHz, 0.0)
    }

    @Test
    fun perfectlyRegular25HzStream() {
        val stats = StreamStats()
        repeat(100) { stats.record(ms(it * 40L)) }
        val s = stats.snapshot()
        assertEquals(100L, s.samples)
        assertEquals(25.0, s.rateHz, 0.01)
        assertEquals(0.0, s.stdMs, 0.001)
        assertEquals(40.0, s.maxMs, 0.001)
        assertEquals(0.0, s.burstPct, 0.001)
    }

    @Test
    fun samplesArrivingInPairsAreCountedAsBursts() {
        val stats = StreamStats()
        // Two samples 2 ms apart, then 78 ms of nothing: still ~25 Hz on average.
        for (k in 0 until 50) {
            stats.record(ms(k * 80L))
            stats.record(ms(k * 80L + 2))
        }
        val s = stats.snapshot()
        assertEquals(25.24, s.rateHz, 0.05)
        assertEquals(78.0, s.maxMs, 0.001)
        assertEquals(50.5, s.burstPct, 0.1)
        assertEquals(38.0, s.stdMs, 0.1)
    }

    @Test
    fun aLongGapIsTreatedAsAReconnectNotJitter() {
        val stats = StreamStats()
        listOf(0L, 40L, 80L, 10_080L, 10_120L, 10_160L).forEach { stats.record(ms(it)) }
        val s = stats.snapshot()
        assertEquals(6L, s.samples)
        assertEquals(40.0, s.meanMs, 0.001)
        assertEquals(25.0, s.rateHz, 0.01)
    }

    @Test
    fun windowOnlyKeepsTheMostRecentIntervals() {
        val stats = StreamStats(windowSize = 10)
        // 20 slow intervals (100 ms), then 10 fast ones (40 ms): the window
        // should only remember the fast ones.
        var t = 0L
        stats.record(ms(t))
        repeat(20) { t += 100; stats.record(ms(t)) }
        repeat(10) { t += 40; stats.record(ms(t)) }
        assertEquals(25.0, stats.snapshot().rateHz, 0.01)
    }
}

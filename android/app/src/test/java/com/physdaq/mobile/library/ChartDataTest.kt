package com.physdaq.mobile.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDataTest {

    @Test
    fun shortInputIsReturnedUnchanged() {
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), ChartData.downsample(floatArrayOf(1f, 2f, 3f), 10), 0f)
    }

    @Test
    fun bucketsAreAveraged() {
        val out = ChartData.downsample(floatArrayOf(1f, 3f, 5f, 7f), 2)
        assertArrayEquals(floatArrayOf(2f, 6f), out, 1e-6f)
    }

    @Test
    fun nanIsIgnoredInsideABucketAndKeptWhenTheWholeBucketIsEmpty() {
        val out = ChartData.downsample(floatArrayOf(Float.NaN, 4f, Float.NaN, Float.NaN), 2)
        assertEquals(4f, out[0], 1e-6f)
        assertTrue(out[1].isNaN())
    }

    @Test
    fun outputNeverExceedsTheLimit() {
        assertEquals(600L, ChartData.downsample(FloatArray(45_000) { it.toFloat() }, 600).size.toLong())
    }

    @Test
    fun durationFormatting() {
        assertEquals("00:00", ChartData.formatDuration(0.0))
        assertEquals("01:05", ChartData.formatDuration(65.9))
        assertEquals("30:00", ChartData.formatDuration(1800.0))
        assertEquals("1:02:03", ChartData.formatDuration(3723.0))
        assertEquals("00:00", ChartData.formatDuration(-4.0))
    }
}

package com.physdaq.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionComparisonTest {

    private val N = Float.NaN

    @Test
    fun tracesStartingInTheSameSecondLineUp() {
        val a = SessionComparison.align(listOf(
            0.19 to floatArrayOf(60f, 61f, 62f),
            0.17 to floatArrayOf(70f, 71f)
        ))
        assertEquals(0L, a.startSec.toLong())
        assertEquals(3L, a.length.toLong())
        assertEquals(70f, a.series[1][0], 0f)
        assertTrue(a.series[1][2].isNaN())
    }

    @Test
    fun aLaterStartIsShiftedRight() {
        val a = SessionComparison.align(listOf(
            0.2 to floatArrayOf(60f, 60f, 60f, 60f),
            2.4 to floatArrayOf(80f, 81f, 82f, 83f)
        ))
        assertEquals(6L, a.length.toLong())
        assertTrue(a.series[1][1].isNaN())
        assertEquals(80f, a.series[1][2], 0f)
        assertEquals(83f, a.series[1][5], 0f)
        assertTrue(a.series[0][5].isNaN())
    }

    @Test
    fun emptyInputGivesNothing() {
        assertEquals(0L, SessionComparison.align(emptyList()).length.toLong())
    }

    @Test
    fun spreadIsOnlyMeasuredWhereTwoOrMoreNodesRead() {
        val g = SessionComparison.agreement(listOf(
            floatArrayOf(N, 68f, 70f, 72f, 75f),
            floatArrayOf(N, N, 71f, 70f, 66f),
            floatArrayOf(N, 69f, N, 71f, 70f)
        ))
        // second 1: 68,69 -> 1 ; 2: 70,71 -> 1 ; 3: 72,70,71 -> 2 ; 4: 75,66,70 -> 9
        assertEquals(4L, g.secondsCompared.toLong())
        assertEquals(1.5, g.medianSpread!!, 1e-9)
        assertEquals(9.0, g.maxSpread!!, 1e-9)
    }

    @Test
    fun meansIgnoreGapsAndAreNullWithoutReadings() {
        val g = SessionComparison.agreement(listOf(
            floatArrayOf(N, 60f, 70f),
            floatArrayOf(N, N, N)
        ))
        assertEquals(65.0, g.means[0]!!, 1e-9)
        assertNull(g.means[1])
        assertEquals(0L, g.secondsCompared.toLong())
        assertNull(g.medianSpread)
    }
}

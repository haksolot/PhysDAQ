package com.physdaq.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun backsOffThenHoldsAtTheCap() {
        val delays = (1..7).map { ReconnectPolicy.delayMs(it) }
        assertEquals(listOf(1000L, 2000L, 4000L, 5000L, 5000L, 5000L, 5000L), delays)
    }

    @Test
    fun oddAttemptNumbersNeverGoNegativeOrHuge() {
        assertEquals(1000L, ReconnectPolicy.delayMs(0))
        assertEquals(1000L, ReconnectPolicy.delayMs(-3))
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayMs(10_000))
    }
}

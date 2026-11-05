package com.physdaq.mobile.ble

/**
 * How long to wait before reconnect attempt number [attempt] (1-based).
 *
 * 1 s, 2 s, 4 s, then a steady 5 s. Quick enough to recover from a blip, and
 * the cap is low on purpose: a node that went to sleep only advertises after
 * someone moves it, so the gaps between attempts should not be long enough to
 * miss that window.
 */
object ReconnectPolicy {
    const val MAX_DELAY_MS = 5_000L

    fun delayMs(attempt: Int): Long {
        val n = (attempt - 1).coerceIn(0, 10)
        return minOf(1_000L shl n, MAX_DELAY_MS)
    }
}

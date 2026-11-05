package com.physdaq.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainWordsTest {

    @Test
    fun signalBands() {
        assertEquals("Strong", PlainWords.signal(-50))
        assertEquals("Strong", PlainWords.signal(-65))
        assertEquals("OK", PlainWords.signal(-66))
        assertEquals("OK", PlainWords.signal(-80))
        assertEquals("Weak", PlainWords.signal(-81))
    }

    @Test
    fun connectionQuality() {
        assertEquals(PlainWords.Quality.NONE, PlainWords.connection(0, 0.0, 0.0))
        assertEquals(PlainWords.Quality.GOOD, PlainWords.connection(1000, 24.3, 120.0))
        assertEquals(PlainWords.Quality.FAIR, PlainWords.connection(1000, 24.3, 800.0))
        assertEquals(PlainWords.Quality.FAIR, PlainWords.connection(1000, 17.0, 200.0))
        assertEquals(PlainWords.Quality.POOR, PlainWords.connection(1000, 24.3, 2500.0))
        assertEquals(PlainWords.Quality.POOR, PlainWords.connection(1000, 9.0, 200.0))
    }

    @Test
    fun numbersReadNaturally() {
        assertEquals("0.2 s", PlainWords.seconds(181.0))
        assertEquals("24 readings/s", PlainWords.rate(24.27))
        assertEquals("worn the whole time", PlainWords.worn(100.0))
        assertEquals("worn 83% of the time", PlainWords.worn(83.2))
        assertEquals("never worn", PlainWords.worn(0.0))
        assertEquals(1L, PlainWords.withinBpm(0.8).toLong())
        assertEquals(1L, PlainWords.withinBpm(0.2).toLong())
        assertEquals(3L, PlainWords.withinBpm(2.6).toLong())
    }
}

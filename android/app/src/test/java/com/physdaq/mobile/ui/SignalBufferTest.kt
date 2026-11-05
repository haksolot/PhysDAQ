package com.physdaq.mobile.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalBufferTest {

    @Test
    fun startsEmpty() {
        val b = SignalBuffer(4)
        assertEquals(0, b.size)
        assertEquals(0, b.snapshot().size)
    }

    @Test
    fun keepsValuesInTimeOrderBeforeItIsFull() {
        val b = SignalBuffer(4)
        b.push(1f); b.push(2f); b.push(3f)
        assertEquals(3, b.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), b.snapshot(), 0f)
    }

    @Test
    fun onceFullItDropsTheOldestAndStaysInOrder() {
        val b = SignalBuffer(4)
        (1..10).forEach { b.push(it.toFloat()) }
        assertEquals(4, b.size)
        assertArrayEquals(floatArrayOf(7f, 8f, 9f, 10f), b.snapshot(), 0f)
    }

    @Test
    fun clearEmptiesItAndItCanBeReused() {
        val b = SignalBuffer(3)
        (1..5).forEach { b.push(it.toFloat()) }
        b.clear()
        assertEquals(0, b.size)
        b.push(42f)
        assertArrayEquals(floatArrayOf(42f), b.snapshot(), 0f)
    }
}

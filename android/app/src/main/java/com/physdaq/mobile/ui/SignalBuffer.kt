package com.physdaq.mobile.ui

/**
 * The most recent [capacity] values of a signal, for a live chart.
 *
 * Charts read from this on their own timer instead of being handed every
 * sample through Compose state. The desktop app learned the hard way that
 * updating UI state per sample at stream rate freezes the interface, so the
 * data path (25 samples a second in) and the draw path (about 25 frames a
 * second out) are kept separate.
 *
 * Main thread only: the runtime writes it and the chart reads it, both there.
 */
class SignalBuffer(val capacity: Int) {

    private val data = FloatArray(capacity)
    private var head = 0          // where the next value goes

    var size = 0
        private set

    fun push(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        head = 0
        size = 0
    }

    /** The values in time order, oldest first. */
    fun snapshot(): FloatArray {
        val out = FloatArray(size)
        val start = (head - size + capacity) % capacity
        for (i in 0 until size) out[i] = data[(start + i) % capacity]
        return out
    }
}

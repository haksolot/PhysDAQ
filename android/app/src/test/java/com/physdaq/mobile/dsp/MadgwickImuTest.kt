package com.physdaq.mobile.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MadgwickImuTest {

    private val g = 9.81
    private val dtNanos = 40_000_000L   // 25 Hz

    @Test
    fun flatAndStillIsZeroTilt() {
        val f = MadgwickImu()
        var t = Tilt.FLAT
        for (i in 0 until 250) t = f.update(i * dtNanos, 0.0, 0.0, g, 0.0, 0.0, 0.0)
        assertEquals(0.0, t.rollDeg, 0.01)
        assertEquals(0.0, t.pitchDeg, 0.01)
        assertEquals(0.0, t.yawDeg, 0.01)
    }

    @Test
    fun staticRollIsReadFromGravityRightAway() {
        val f = MadgwickImu()
        val a = Math.toRadians(30.0)
        val t = f.update(0L, 0.0, g * sin(a), g * cos(a), 0.0, 0.0, 0.0)
        assertEquals(30.0, t.rollDeg, 0.01)
        assertEquals(0.0, t.pitchDeg, 0.01)
    }

    @Test
    fun staticPitchHasTheDocumentedSign() {
        val f = MadgwickImu()
        val a = Math.toRadians(20.0)
        // +X pointing down means ax is negative.
        val t = f.update(0L, -g * sin(a), 0.0, g * cos(a), 0.0, 0.0, 0.0)
        assertEquals(20.0, t.pitchDeg, 0.01)
        assertEquals(0.0, t.rollDeg, 0.01)
    }

    @Test
    fun yawFollowsTheGyroWhenGravityAgrees() {
        val f = MadgwickImu()
        var t = Tilt.FLAT
        // 1 s turning about Z at 90 deg/s; gravity stays on Z.
        for (i in 0..25) t = f.update(i * dtNanos, 0.0, 0.0, g, 0.0, 0.0, PI / 2)
        assertEquals(90.0, t.yawDeg, 2.0)
        assertEquals(0.0, t.rollDeg, 0.5)
    }

    @Test
    fun rollFollowsGyroAndAccelerometerTogether() {
        val f = MadgwickImu()
        var t = Tilt.FLAT
        val rate = Math.toRadians(45.0)   // rad/s about X
        for (i in 0..50) {               // 2 s -> 90 deg
            val angle = rate * (i * 0.04)
            t = f.update(i * dtNanos, 0.0, g * sin(angle), g * cos(angle), rate, 0.0, 0.0)
        }
        assertEquals(90.0, t.rollDeg, 2.0)
    }

    @Test
    fun accelerometerCorrectsAWrongStart() {
        val f = MadgwickImu()
        f.update(0L, 0.0, 0.0, g, 0.0, 0.0, 0.0)              // thinks it is flat
        var t = Tilt.FLAT
        val a = Math.toRadians(40.0)
        // gyro says nothing moved, but gravity now says 40 deg: the filter must converge to it
        for (i in 1..400) t = f.update(i * dtNanos, 0.0, g * sin(a), g * cos(a), 0.0, 0.0, 0.0)
        assertEquals(40.0, t.rollDeg, 1.0)
    }

    @Test
    fun zeroAccelerometerDoesNotProduceNaN() {
        val f = MadgwickImu()
        var t = Tilt.FLAT
        for (i in 0 until 20) t = f.update(i * dtNanos, 0.0, 0.0, 0.0, 0.1, 0.0, 0.0)
        assertFalse(t.rollDeg.isNaN() || t.pitchDeg.isNaN() || t.yawDeg.isNaN())
    }

    @Test
    fun aLongGapIsNotIntegratedAsAHugeRotation() {
        val f = MadgwickImu()
        f.update(0L, 0.0, 0.0, g, 0.0, 0.0, 0.0)
        // 60 s gap with 1 rad/s about Z: capped to maxDt (0.2 s), so at most ~11 degrees
        val t = f.update(60_000_000_000L, 0.0, 0.0, g, 0.0, 0.0, 1.0)
        assertTrue(t.yawDeg < 12.0)
    }

    @Test
    fun resetStartsOverFromTheNextReading() {
        val f = MadgwickImu()
        f.update(0L, 0.0, g * sin(0.5), g * cos(0.5), 0.0, 0.0, 0.0)
        f.reset()
        val t = f.update(1_000L, 0.0, 0.0, g, 0.0, 0.0, 0.0)
        assertEquals(0.0, t.rollDeg, 0.01)
    }

    @Test
    fun quaternionStaysUnitLength() {
        val f = MadgwickImu()
        var t = Tilt.FLAT
        for (i in 0 until 2000) {
            t = f.update(i * dtNanos, 0.3 * sin(i * 0.1), 0.2, g, 0.5 * cos(i * 0.07), 0.3, -0.4)
        }
        val n = t.qw * t.qw + t.qx * t.qx + t.qy * t.qy + t.qz * t.qz
        assertEquals(1.0, n, 1e-9)
    }
}

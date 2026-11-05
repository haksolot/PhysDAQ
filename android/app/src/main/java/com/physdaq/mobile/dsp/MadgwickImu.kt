package com.physdaq.mobile.dsp

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * How a sensor is tilted, in degrees.
 *
 * Axes follow the board: with the board flat and still, gravity reads +Z (the
 * data shows az of about +9.8), so roll and pitch are both 0.
 *
 *  - [rollDeg]  rotation about X: positive when +Y points up (ay > 0)
 *  - [pitchDeg] rotation about Y: positive when +X points down (ax < 0)
 *  - [yawDeg]   rotation about Z. There is no magnetometer, so nothing anchors it:
 *               it is "turned since the app started" and slowly drifts. Do not trust
 *               it for more than a few minutes.
 */
data class Tilt(
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
    val qw: Double, val qx: Double, val qy: Double, val qz: Double
) {
    companion object {
        val FLAT = Tilt(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Madgwick's orientation filter in its 6-axis (gyro + accelerometer) form.
 *
 * The gyroscope is integrated for fast changes; the accelerometer's measure of
 * "which way is down" pulls the estimate back so roll and pitch do not drift.
 * [beta] sets how hard it pulls (0.1 is a common default; higher follows the
 * accelerometer faster but is noisier while the wearer moves).
 *
 * Inputs are in the units the device sends: gyro in rad/s, accelerometer in
 * m/s^2 (only its direction is used). Time comes from the samples' own host
 * timestamps; a gap longer than [maxDt] is treated as a pause and stepped over
 * with a short interval rather than integrating a huge angle.
 *
 * Pure Kotlin, not thread-safe: feed it from one place, in time order.
 */
class MadgwickImu(private val beta: Double = 0.1, private val maxDt: Double = 0.2) {

    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0
    private var lastNanos = 0L
    private var started = false

    /** Forget the orientation, e.g. when the link drops and a new one starts. */
    fun reset() {
        q0 = 1.0; q1 = 0.0; q2 = 0.0; q3 = 0.0
        started = false
    }

    fun update(tNanos: Long, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): Tilt {
        val accNorm = sqrt(ax * ax + ay * ay + az * az)

        if (!started) {
            started = true
            lastNanos = tNanos
            // Start from the tilt the accelerometer shows, so there is no long settling.
            if (accNorm > 1e-6) initFromGravity(ax / accNorm, ay / accNorm, az / accNorm)
            return tilt()
        }

        val dt = ((tNanos - lastNanos) / 1e9).coerceIn(0.0, maxDt)
        lastNanos = tNanos
        if (dt == 0.0) return tilt()

        var qDot0 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot1 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        var qDot2 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        var qDot3 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)

        if (accNorm > 1e-6) {
            val nx = ax / accNorm
            val ny = ay / accNorm
            val nz = az / accNorm

            // Error between the measured and the predicted direction of gravity.
            val f1 = 2.0 * (q1 * q3 - q0 * q2) - nx
            val f2 = 2.0 * (q0 * q1 + q2 * q3) - ny
            val f3 = 2.0 * (0.5 - q1 * q1 - q2 * q2) - nz

            var s0 = -2.0 * q2 * f1 + 2.0 * q1 * f2
            var s1 = 2.0 * q3 * f1 + 2.0 * q0 * f2 - 4.0 * q1 * f3
            var s2 = -2.0 * q0 * f1 + 2.0 * q3 * f2 - 4.0 * q2 * f3
            var s3 = 2.0 * q1 * f1 + 2.0 * q2 * f2
            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 1e-12) {
                s0 /= sNorm; s1 /= sNorm; s2 /= sNorm; s3 /= sNorm
                qDot0 -= beta * s0
                qDot1 -= beta * s1
                qDot2 -= beta * s2
                qDot3 -= beta * s3
            }
        }

        q0 += qDot0 * dt
        q1 += qDot1 * dt
        q2 += qDot2 * dt
        q3 += qDot3 * dt
        val n = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= n; q1 /= n; q2 /= n; q3 /= n
        return tilt()
    }

    /** Quaternion with the given roll/pitch and zero yaw, from a unit gravity reading. */
    private fun initFromGravity(nx: Double, ny: Double, nz: Double) {
        val roll = atan2(ny, nz)
        val pitch = asin((-nx).coerceIn(-1.0, 1.0))
        val cr = kotlin.math.cos(roll / 2); val sr = kotlin.math.sin(roll / 2)
        val cp = kotlin.math.cos(pitch / 2); val sp = kotlin.math.sin(pitch / 2)
        // yaw = 0
        q0 = cr * cp
        q1 = sr * cp
        q2 = cr * sp
        q3 = -sr * sp
    }

    private fun tilt(): Tilt {
        val toDeg = 180.0 / PI
        val roll = atan2(2.0 * (q0 * q1 + q2 * q3), 1.0 - 2.0 * (q1 * q1 + q2 * q2))
        val pitch = asin((2.0 * (q0 * q2 - q3 * q1)).coerceIn(-1.0, 1.0))
        val yaw = atan2(2.0 * (q0 * q3 + q1 * q2), 1.0 - 2.0 * (q2 * q2 + q3 * q3))
        return Tilt(roll * toDeg, pitch * toDeg, yaw * toDeg, q0, q1, q2, q3)
    }
}

package com.physdaq.mobile.library

import com.physdaq.mobile.dsp.HeartRateEstimator
import com.physdaq.mobile.dsp.MadgwickImu
import com.physdaq.mobile.dsp.PpgDisplayFilter
import java.io.BufferedReader
import java.io.Reader

/**
 * Reads a recording CSV and works out what the Library shows about it.
 *
 * Two layouts are understood, found by column NAME (never by position):
 *
 *  - the phone's logger layout   `timestamp,red,ir,ax,ay,az,gx,gy,gz`
 *    (time = `timestamp`, a plain float in seconds)
 *  - the desktop app's layout    `timestamp,elapsed_s,ax,...,ppg_red,ppg_ir,ppg_filt,bpm,contact`
 *    (time = `elapsed_s`; its `timestamp` is an ISO string and is ignored)
 *
 * Time and IR are required. If the six motion columns (ax..gz) are present too, tilt
 * (roll and pitch) is recomputed with [MadgwickImu]. Heart rate is recomputed from IR with the
 * same [HeartRateEstimator] the live screen uses, so a replayed recording shows
 * what the live screen would have shown.
 *
 * Rows that do not parse, or whose time goes backwards, are skipped and counted.
 * Pure Kotlin: no Android types, runs in JVM unit tests.
 */
object CsvAnalyzer {

    /** IR above this means "sensor on skin" (same constant as firmware, bridge and pipeline). */
    const val CONTACT_IR_MIN = 5000.0

    sealed class Result {
        class Ok(val analysis: RecordingAnalysis) : Result()
        /** [reason] is written for the person using the app. */
        class Unsupported(val reason: String) : Result()
    }

    fun analyze(reader: Reader): Result {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader)

        var header: String? = null
        while (header == null) {
            val line = br.readLine() ?: return Result.Unsupported("The file is empty.")
            val clean = line.removePrefix("﻿").trim()
            if (clean.isNotEmpty()) header = clean
        }
        val cols = header.split(',').map { it.trim().lowercase() }

        val timeIdx = cols.indexOf("elapsed_s").takeIf { it >= 0 } ?: cols.indexOf("timestamp")
        val irIdx = cols.indexOf("ir").takeIf { it >= 0 } ?: cols.indexOf("ppg_ir")
        if (timeIdx < 0 || irIdx < 0) {
            return Result.Unsupported(
                "This is not a PhysDAQ recording. Expected a header with a time column " +
                    "(timestamp or elapsed_s) and an IR column (ir or ppg_ir), for example " +
                    "timestamp,red,ir,ax,ay,az,gx,gy,gz."
            )
        }
        val motionIdx = listOf("ax", "ay", "az", "gx", "gy", "gz").map { cols.indexOf(it) }
        val hasMotion = motionIdx.all { it >= 0 }
        val need = maxOf(timeIdx, irIdx, if (hasMotion) motionIdx.max() else 0)

        val times = DoubleList()
        val irs = DoubleList()
        val motion = Array(6) { DoubleList() }
        var skipped = 0
        while (true) {
            val line = br.readLine() ?: break
            if (line.isBlank()) continue
            val f = line.split(',')
            if (f.size <= need) { skipped++; continue }
            val t = f[timeIdx].trim().toDoubleOrNull()
            val ir = f[irIdx].trim().toDoubleOrNull()
            // Motion values that do not parse count as 0 rather than dropping the sample: tilt is a bonus.
            val m = if (hasMotion) DoubleArray(6) { f[motionIdx[it]].trim().toDoubleOrNull() ?: 0.0 } else null
            if (t == null || ir == null || t.isNaN() || ir.isNaN() ||
                (times.size > 0 && t < times[times.size - 1])
            ) { skipped++; continue }
            times.add(t)
            irs.add(ir)
            if (m != null) for (k in 0 until 6) motion[k].add(m[k])
        }
        if (times.size == 0) {
            return Result.Unsupported(
                if (skipped > 0) "No row could be read. The time or IR values are not numbers."
                else "The file has a header but no samples."
            )
        }
        return Result.Ok(build(times, irs, if (hasMotion) motion else null, skipped))
    }

    private fun build(times: DoubleList, irs: DoubleList, motion: Array<DoubleList>?, skipped: Int): RecordingAnalysis {
        val n = times.size
        val t0 = times[0]
        val duration = times[n - 1] - t0
        val rate = if (n > 1 && duration > 0.0) (n - 1) / duration else 0.0

        var maxGap = 0.0
        var worn = 0
        for (i in 0 until n) {
            if (irs[i] > CONTACT_IR_MIN) worn++
            if (i > 0) maxGap = maxOf(maxGap, (times[i] - times[i - 1]) * 1000.0)
        }

        val filter = PpgDisplayFilter()
        if (rate > 0.0) filter.setSampleRate(rate)
        val filtered = FloatArray(n)

        val estimator = HeartRateEstimator()
        val imu = if (motion != null) MadgwickImu() else null
        val bins = if (duration > 0.0) duration.toInt() + 1 else 1
        val trace = FloatArray(bins) { Float.NaN }
        val rollTrace = if (imu != null) FloatArray(bins) { Float.NaN } else null
        val pitchTrace = if (imu != null) FloatArray(bins) { Float.NaN } else null
        for (i in 0 until n) {
            filtered[i] = filter.process(irs[i]).toFloat()
            val r = estimator.update(((times[i] - t0) * 1e9).toLong(), irs[i])
            if (imu != null && motion != null && rollTrace != null && pitchTrace != null) {
                val tilt = imu.update(
                    ((times[i] - t0) * 1e9).toLong(),
                    motion[0][i], motion[1][i], motion[2][i], motion[3][i], motion[4][i], motion[5][i]
                )
                val bin = ((times[i] - t0).toInt()).coerceIn(0, bins - 1)
                rollTrace[bin] = tilt.rollDeg.toFloat()      // the last sample of each second wins
                pitchTrace[bin] = tilt.pitchDeg.toFloat()
            }
            val bpm = r.bpm
            if (r.estimated && bpm != null) {
                trace[((times[i] - t0).toInt()).coerceIn(0, bins - 1)] = bpm.toFloat()
            }
        }

        var sum = 0.0
        var count = 0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in trace) {
            if (v.isNaN()) continue
            sum += v; count++
            lo = minOf(lo, v.toDouble()); hi = maxOf(hi, v.toDouble())
        }

        val summary = RecordingSummary(
            samples = n,
            durationSec = duration,
            rateHz = rate,
            maxGapMs = maxGap,
            wornPct = 100.0 * worn / n,
            bpmMean = if (count > 0) sum / count else null,
            bpmMin = if (count > 0) lo else null,
            bpmMax = if (count > 0) hi else null,
            skippedRows = skipped
        )
        return RecordingAnalysis(summary, filtered, trace, rollTrace, pitchTrace, startSec = t0)
    }

    /** Growable primitive array, so a 45,000-row file does not box 45,000 Doubles. */
    private class DoubleList {
        private var a = DoubleArray(1024)
        var size = 0
            private set
        fun add(v: Double) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        operator fun get(i: Int): Double = a[i]
    }
}

/** Numbers shown for one recording. */
data class RecordingSummary(
    val samples: Int,
    val durationSec: Double,
    /** (samples-1) / duration: the real average rate, not the nominal 25 Hz. */
    val rateHz: Double,
    val maxGapMs: Double,
    /** Share of samples with IR above the contact threshold. */
    val wornPct: Double,
    val bpmMean: Double?,
    val bpmMin: Double?,
    val bpmMax: Double?,
    val skippedRows: Int
)

/**
 * A recording ready to draw.
 *
 * @property filtered the band-passed IR, one value per sample (the "PPG, filtered" chart)
 * @property bpmTrace one value per second of the recording; NaN where there was no reading
 * @property rollTrace see [pitchTrace]; both have the same length as [bpmTrace]
 */
class RecordingAnalysis(
    val summary: RecordingSummary,
    val filtered: FloatArray,
    val bpmTrace: FloatArray,
    /** Tilt once per second, in degrees; null if the file has no motion columns. Yaw is left out: it drifts. */
    val rollTrace: FloatArray? = null,
    val pitchTrace: FloatArray? = null,
    /** Time of the first sample, in session seconds: slot 0 of the traces is this second. */
    val startSec: Double = 0.0
) {
    /** About [lenSec] seconds of the filtered signal starting [startSec] in (clamped to the recording). */
    fun filteredWindow(startSec: Double, lenSec: Double): FloatArray {
        val rate = summary.rateHz
        if (rate <= 0.0 || filtered.isEmpty()) return filtered.copyOf()
        val len = (lenSec * rate).toInt().coerceIn(2, filtered.size)
        val start = (startSec * rate).toInt().coerceIn(0, filtered.size - len)
        return filtered.copyOfRange(start, start + len)
    }
}

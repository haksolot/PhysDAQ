package com.physdaq.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

class CsvAnalyzerTest {

    private val loggerHeader = "timestamp,red,ir,ax,ay,az,gx,gy,gz"

    /** A clean pulse at [hz] sampled at [rate]; IR sits on a 50,000 baseline. */
    private fun loggerCsv(seconds: Double, rate: Double = 24.3, hz: Double = 1.2, baseline: Int = 50000): String {
        val sb = StringBuilder(loggerHeader).append('\n')
        val n = (seconds * rate).toInt()
        for (i in 0 until n) {
            val t = i / rate
            val ir = (baseline + 2000 * sin(2 * PI * hz * t)).toInt()
            sb.append(String.format(Locale.ROOT, "%.4f,%d,%d,0.0,0.0,9.8,0.0,0.0,0.0\n", t, ir - 100, ir))
        }
        return sb.toString()
    }

    private fun ok(csv: String): RecordingAnalysis {
        val r = CsvAnalyzer.analyze(StringReader(csv))
        assertTrue("expected Ok but was " + (r as? CsvAnalyzer.Result.Unsupported)?.reason, r is CsvAnalyzer.Result.Ok)
        return (r as CsvAnalyzer.Result.Ok).analysis
    }

    private fun reason(csv: String): String {
        val r = CsvAnalyzer.analyze(StringReader(csv))
        assertTrue("expected Unsupported", r is CsvAnalyzer.Result.Unsupported)
        return (r as CsvAnalyzer.Result.Unsupported).reason
    }

    @Test
    fun summaryOfCleanLoggerFile() {
        val a = ok(loggerCsv(60.0))
        val s = a.summary
        assertEquals(1458L, s.samples.toLong())
        assertEquals(60.0, s.durationSec, 0.1)
        assertEquals(24.3, s.rateHz, 0.05)
        assertEquals(100.0, s.wornPct, 0.001)
        assertEquals(0L, s.skippedRows.toLong())
        assertEquals(41.0, s.maxGapMs, 1.0)
    }

    @Test
    fun heartRateIsRecoveredFromIr() {
        val s = ok(loggerCsv(60.0)).summary
        assertNotNull(s.bpmMean)
        assertEquals(72.0, s.bpmMean!!, 2.0)
        assertTrue(s.bpmMin!! <= s.bpmMean!! && s.bpmMean!! <= s.bpmMax!!)
    }

    @Test
    fun notWornFileHasNoHeartRate() {
        val s = ok(loggerCsv(30.0, baseline = 800)).summary
        assertEquals(0.0, s.wornPct, 0.001)
        assertNull(s.bpmMean)
    }

    @Test
    fun traceHasOneSlotPerSecondAndGapsBeforeFirstReading() {
        val a = ok(loggerCsv(30.0))
        assertEquals(30L, a.bpmTrace.size.toLong())
        assertTrue("first seconds have no reading yet", a.bpmTrace[0].isNaN())
        assertTrue(!a.bpmTrace[a.bpmTrace.size - 1].isNaN())
    }

    @Test
    fun filteredSignalHasOneValuePerSampleAndWindowIsClamped() {
        val a = ok(loggerCsv(30.0))
        assertEquals(a.summary.samples.toLong(), a.filtered.size.toLong())
        assertEquals(194L, a.filteredWindow(5.0, 8.0).size.toLong())   // 8 s at 24.3 Hz
        assertEquals(a.filtered.size.toLong(), a.filteredWindow(0.0, 9999.0).size.toLong())
        // a start past the end slides back so the window still fits
        assertEquals(194L, a.filteredWindow(500.0, 8.0).size.toLong())
    }

    @Test
    fun desktopLayoutIsReadByColumnName() {
        val sb = StringBuilder("timestamp,elapsed_s,ax,ay,az,gx,gy,gz,qw,qx,qy,qz,ppg_red,ppg_ir,ppg_filt,bpm,contact\n")
        val rate = 24.3
        for (i in 0 until (40 * rate).toInt()) {
            val t = i / rate
            val ir = (50000 + 2000 * sin(2 * PI * 1.2 * t)).toInt()
            sb.append(String.format(Locale.ROOT,
                "2026-10-07T14-32-10-000Z,%.3f,0,0,9.8,0,0,0,1,0,0,0,%d,%d,0,,1\n", t, ir, ir))
        }
        val s = ok(sb.toString()).summary
        assertEquals(40.0, s.durationSec, 0.1)
        assertEquals(72.0, s.bpmMean!!, 2.0)
    }

    @Test
    fun unknownLayoutGivesAFriendlyReason() {
        val r = reason("a,b,c\n1,2,3\n")
        assertTrue(r, r.contains("not a PhysDAQ recording"))
    }

    @Test
    fun emptyAndHeaderOnlyFiles() {
        assertEquals("The file is empty.", reason(""))
        assertEquals("The file has a header but no samples.", reason("$loggerHeader\n"))
    }

    @Test
    fun badRowsAreSkippedAndCounted() {
        val csv = "$loggerHeader\n" +
            "0.0000,1,60000,0,0,0,0,0,0\n" +
            "oops\n" +
            "0.0400,1,abc,0,0,0,0,0,0\n" +
            "0.0800,1,60000,0,0,0,0,0,0\n" +
            "0.0500,1,60000,0,0,0,0,0,0\n" +     // time went backwards
            "\n" +
            "0.1200,1,60000,0,0,0,0,0,0\n"
        val s = ok(csv).summary
        assertEquals(3L, s.samples.toLong())
        assertEquals(3L, s.skippedRows.toLong())
    }

    @Test
    fun byteOrderMarkAndWindowsLineEndingsAreFine() {
        val csv = "﻿" + loggerCsv(10.0).replace("\n", "\r\n")
        assertEquals(243L, ok(csv).summary.samples.toLong())
    }

    @Test
    fun singleSampleDoesNotDivideByZero() {
        val s = ok("$loggerHeader\n0.0,1,60000,0,0,0,0,0,0\n").summary
        assertEquals(1L, s.samples.toLong())
        assertEquals(0.0, s.rateHz, 0.0)
        assertEquals(0.0, s.durationSec, 0.0)
    }

    @Test
    fun tiltTracesComeFromTheMotionColumns() {
        // Sensor lying tilted 30 degrees about X: ay = g sin30, az = g cos30
        val sb = StringBuilder(loggerHeader).append('\n')
        for (i in 0 until 600) {
            sb.append(String.format(Locale.ROOT, "%.4f,1,60000,0.000,%.3f,%.3f,0.000,0.000,0.000\n",
                i / 24.3, 9.81 * sin(PI / 6), 9.81 * kotlin.math.cos(PI / 6)))
        }
        val a = ok(sb.toString())
        val roll = a.rollTrace!!
        val pitch = a.pitchTrace!!
        assertEquals(roll.size.toLong(), a.bpmTrace.size.toLong())
        assertEquals(30.0, roll[roll.size - 1].toDouble(), 0.5)
        assertEquals(0.0, pitch[pitch.size - 1].toDouble(), 0.5)
    }

    @Test
    fun filesWithoutMotionColumnsHaveNoTilt() {
        val a = ok("timestamp,ir\n0.0,60000\n0.04,60000\n")
        assertNull(a.rollTrace)
        assertNull(a.pitchTrace)
    }
}

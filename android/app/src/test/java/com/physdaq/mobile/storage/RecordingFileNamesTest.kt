package com.physdaq.mobile.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class RecordingFileNamesTest {

    private fun date() = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 7, 14, 32, 10) }.time

    @Test
    fun stampUsesTheLoggersPattern() {
        assertEquals("2026-10-07_14-32-10", RecordingFileNames.stamp(date()))
    }

    @Test
    fun stampHasNoCharactersThatBreakFileNames() {
        val s = RecordingFileNames.stamp(Date())
        assertTrue(s.none { it == ':' || it == ' ' || it == '/' })
    }

    @Test
    fun deviceFileAddsTheDeviceNameAfterTheSharedStem() {
        assertEquals(
            "2026-10-07_14-32-10_PhysDAQ-FDF9.csv",
            RecordingFileNames.forDevice("2026-10-07_14-32-10", "PhysDAQ-FDF9")
        )
    }

    @Test
    fun sanitizeKeepsNormalNamesAndReplacesTheRest() {
        assertEquals("PhysDAQ-Hub-FDF9", RecordingFileNames.sanitize("PhysDAQ-Hub-FDF9"))
        assertEquals("My_node__1", RecordingFileNames.sanitize("My node #1"))
        assertEquals("AA_BB_CC", RecordingFileNames.sanitize("AA:BB:CC"))
    }

    @Test
    fun anEmptyOrAllPunctuationLabelFallsBackToDevice() {
        assertEquals("device", RecordingFileNames.sanitize(""))
        assertEquals("device", RecordingFileNames.sanitize("::"))
    }

    // --- session name in the stem

    @Test
    fun stemPutsTheTimestampFirstThenTheSessionName() {
        assertEquals("2026-10-07_14-32-10_PhysDAQ_Session", RecordingFileNames.stem(date(), "PhysDAQ_Session"))
        assertEquals("2026-10-07_14-32-10_Resting_HR", RecordingFileNames.stem(date(), "Resting HR"))
    }

    @Test
    fun aBlankSessionNameLeavesJustTheTimestamp() {
        assertEquals("2026-10-07_14-32-10", RecordingFileNames.stem(date(), ""))
        assertEquals("2026-10-07_14-32-10", RecordingFileNames.stem(date(), "  ::  "))
    }

    // --- reading a name back (used by the Library)

    @Test
    fun parseSplitsTimestampSessionAndPosition() {
        val p = RecordingFileNames.parse("2026-10-07_14-32-10_PhysDAQ_Session_wrist-left.csv")
        assertNotNull(p)
        assertEquals("2026-10-07_14-32-10", p!!.stamp)
        assertEquals("PhysDAQ_Session", p.sessionName)
        assertEquals("wrist-left", p.position)
        assertEquals("2026-10-07_14-32-10_PhysDAQ_Session", p.sessionKey)
    }

    @Test
    fun parseWorksWithoutASessionName() {
        val p = RecordingFileNames.parse("2026-10-07_14-32-10_chest.csv")!!
        assertEquals("", p.sessionName)
        assertEquals("chest", p.position)
        assertEquals("2026-10-07_14-32-10", p.sessionKey)
    }

    @Test
    fun filesOfOneTakeShareASessionKey() {
        val a = RecordingFileNames.parse(RecordingFileNames.forDevice(RecordingFileNames.stem(date(), "Run 1"), "wrist-left"))!!
        val b = RecordingFileNames.parse(RecordingFileNames.forDevice(RecordingFileNames.stem(date(), "Run 1"), "chest"))!!
        assertEquals(a.sessionKey, b.sessionKey)
    }

    @Test
    fun parseRoundTripsNamesWithUnderscoresAndHyphens() {
        val file = RecordingFileNames.forDevice(RecordingFileNames.stem(date(), "Test-1_final"), "finger-right")
        val p = RecordingFileNames.parse(file)!!
        assertEquals("Test-1_final", p.sessionName)
        assertEquals("finger-right", p.position)
    }

    @Test
    fun parseReadsOldStyleFilesNamedAfterTheDevice() {
        val p = RecordingFileNames.parse("2026-10-07_14-32-10_PhysDAQ-FDF9.csv")!!
        assertEquals("", p.sessionName)
        assertEquals("PhysDAQ-FDF9", p.position)
    }

    @Test
    fun parseIgnoresPipelineOutputsAndOtherFiles() {
        assertNull(RecordingFileNames.parse("2026-10-07_14-32-10_wrist-left_enriched.csv"))
        assertNull(RecordingFileNames.parse("2026-10-07_14-32-10_wrist-left_beats.csv"))
        assertNull(RecordingFileNames.parse("2026-10-07_14-32-10_wrist-left_bpm_spectral.csv"))
        assertNull(RecordingFileNames.parse("test1.csv"))
        assertNull(RecordingFileNames.parse("2026-10-07_14-32-10.csv"))
        assertNull(RecordingFileNames.parse("notes.txt"))
    }

    @Test
    fun parseStampGivesTheDateBackAndRejectsJunk() {
        val d = RecordingFileNames.parseStamp("2026-10-07_14-32-10")
        assertEquals(date().time, d!!.time)
        assertNull(RecordingFileNames.parseStamp("2026-13-45_99-99-99"))
        assertNull(RecordingFileNames.parseStamp("not a stamp"))
    }
}

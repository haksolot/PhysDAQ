package com.physdaq.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupsTest {

    private fun f(name: String, size: Long = 100) = LibraryFile(name, "content://x/$name", size)

    @Test
    fun filesOfOneTakeAreGroupedAndNewestComesFirst() {
        val groups = SessionGroups.group(listOf(
            f("2026-10-07_14-32-10_PhysDAQ_Session_chest.csv"),
            f("2026-10-08_09-00-00_Run_wrist-left.csv"),
            f("2026-10-07_14-32-10_PhysDAQ_Session_wrist-left.csv")
        ))
        assertEquals(2L, groups.size.toLong())
        assertEquals("Run", groups[0].title)
        assertEquals("PhysDAQ Session", groups[1].title)
        assertEquals(2L, groups[1].members.size.toLong())
    }

    @Test
    fun membersFollowTheBodyPositionOrder() {
        val g = SessionGroups.group(listOf(
            f("2026-10-07_14-32-10_S_wrist-left.csv"),
            f("2026-10-07_14-32-10_S_head.csv"),
            f("2026-10-07_14-32-10_S_chest.csv")
        )).single()
        assertEquals(listOf("Ear / Head", "Chest (Torso)", "Left Wrist"), g.members.map { it.label })
    }

    @Test
    fun unnamedSessionHasATitleAndADate() {
        val g = SessionGroups.group(listOf(f("2026-10-07_14-32-10_chest.csv"))).single()
        assertEquals("Untitled session", g.title)
        assertNotNull(g.date)
        assertTrue(g.isRecording)
    }

    @Test
    fun unknownPositionKeepsItsRawLabel() {
        val g = SessionGroups.group(listOf(f("2026-10-07_14-32-10_S_elbow.csv"))).single()
        assertEquals("elbow", g.members[0].label)
        assertNull(g.members[0].position)
    }

    @Test
    fun pipelineOutputsAndOtherCsvFilesGoLastOneEach() {
        val groups = SessionGroups.group(listOf(
            f("test1.csv"),
            f("2026-10-07_14-32-10_S_chest_enriched.csv"),
            f("2026-10-07_14-32-10_S_chest.csv")
        ))
        assertEquals(3L, groups.size.toLong())
        assertTrue(groups[0].isRecording)
        assertFalse(groups[1].isRecording)
        assertFalse(groups[2].isRecording)
        assertEquals(listOf("2026-10-07_14-32-10_S_chest_enriched.csv", "test1.csv"), groups.drop(1).map { it.title })
    }

    @Test
    fun sizeIsTheSumOfTheFiles() {
        val g = SessionGroups.group(listOf(
            f("2026-10-07_14-32-10_S_chest.csv", 300),
            f("2026-10-07_14-32-10_S_head.csv", 200)
        )).single()
        assertEquals(500L, g.sizeBytes)
    }

    @Test
    fun emptyListGivesNoGroups() {
        assertTrue(SessionGroups.group(emptyList()).isEmpty())
    }
}

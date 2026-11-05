package com.physdaq.mobile.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeChecksTest {

    @Test
    fun statusEightMeansProbablyAsleep() {
        assertTrue(NodeChecks.looksAsleep("Link lost (status 8)"))
        assertTrue(NodeChecks.looksAsleep("Link lost, status=8"))
        assertTrue(NodeChecks.looksAsleep("link lost (status 0x08)"))
        assertTrue(NodeChecks.looksAsleep("Connection supervision timeout"))
    }

    @Test
    fun otherStatusesAreNotSleep() {
        assertFalse(NodeChecks.looksAsleep("Link lost (status 19)"))
        assertFalse(NodeChecks.looksAsleep("Link lost (status 133)"))
        assertFalse(NodeChecks.looksAsleep("Link lost (status 80)"))
        assertFalse(NodeChecks.looksAsleep("Service discovery failed"))
    }

    @Test
    fun allWornAndConnectedIsOk() {
        val p = NodeChecks.preflight(listOf(
            NodeChecks.Slot("Chest (Torso)", connected = true, worn = true),
            NodeChecks.Slot("Left Wrist", connected = true, worn = true)
        ))
        assertTrue(p.ok)
        assertEquals(2L, p.recordable.toLong())
    }

    @Test
    fun notWornAndDisconnectedAreListedInOrder() {
        val p = NodeChecks.preflight(listOf(
            NodeChecks.Slot("Chest (Torso)", connected = true, worn = true),
            NodeChecks.Slot("Left Wrist", connected = true, worn = false),
            NodeChecks.Slot("Right Ankle", connected = false, worn = false)
        ))
        assertFalse(p.ok)
        assertEquals(
            listOf("Left Wrist is not worn.", "Right Ankle is disconnected and will not be recorded."),
            p.issues
        )
        assertEquals(2L, p.recordable.toLong())
    }

    @Test
    fun nothingConnectedMeansNothingToRecord() {
        val p = NodeChecks.preflight(listOf(NodeChecks.Slot("Head", connected = false, worn = false)))
        assertEquals(0L, p.recordable.toLong())
    }
}

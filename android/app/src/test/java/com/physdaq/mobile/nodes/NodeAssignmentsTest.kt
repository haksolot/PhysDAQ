package com.physdaq.mobile.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeAssignmentsTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
    }

    @Test
    fun startsEmpty() {
        assertNull(NodeAssignments(MapStore()).positionOf("E2:51:4E:B9:29:00"))
    }

    @Test
    fun assignIsRememberedAcrossInstances() {
        val store = MapStore()
        NodeAssignments(store).assign("E2:51:4E:B9:29:00", NodePosition.WRIST_LEFT)
        // A new instance on the same store is what an app restart looks like.
        assertEquals(NodePosition.WRIST_LEFT, NodeAssignments(store).positionOf("E2:51:4E:B9:29:00"))
    }

    @Test
    fun relinkingMovesTheDevice() {
        val a = NodeAssignments(MapStore())
        a.assign("AA", NodePosition.CHEST)
        a.assign("AA", NodePosition.HEAD)
        assertEquals(NodePosition.HEAD, a.positionOf("AA"))
    }

    @Test
    fun severalDevicesKeepTheirOwnPositions() {
        val store = MapStore()
        val a = NodeAssignments(store)
        a.assign("AA:01", NodePosition.WRIST_LEFT)
        a.assign("BB:02", NodePosition.WRIST_RIGHT)
        val b = NodeAssignments(store)
        assertEquals(NodePosition.WRIST_LEFT, b.positionOf("AA:01"))
        assertEquals(NodePosition.WRIST_RIGHT, b.positionOf("BB:02"))
    }

    @Test
    fun forgetRemovesIt() {
        val store = MapStore()
        val a = NodeAssignments(store)
        a.assign("AA", NodePosition.CHEST)
        a.forget("AA")
        assertNull(NodeAssignments(store).positionOf("AA"))
    }

    @Test
    fun garbageInTheStoreIsIgnored() {
        val store = MapStore()
        store.put("node_positions", ";;=;AA=chest;BB=elbow;nonsense;=head")
        val a = NodeAssignments(store)
        assertEquals(NodePosition.CHEST, a.positionOf("AA"))
        assertNull(a.positionOf("BB"))
    }
}

package com.physdaq.mobile.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodePositionTest {

    @Test
    fun hasTheDesktopsEightPositions() {
        assertEquals(8, NodePosition.values().size)
    }

    @Test
    fun slugsAreUniqueAndFileNameSafe() {
        val slugs = NodePosition.values().map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        // No underscores: a file name splits on its last underscore to find the position.
        slugs.forEach { assertTrue(it, it.matches(Regex("[a-z]+(-[a-z]+)?"))) }
    }

    @Test
    fun slugRoundTrips() {
        NodePosition.values().forEach { assertEquals(it, NodePosition.fromSlug(it.slug)) }
    }

    @Test
    fun unknownSlugIsNull() {
        assertNull(NodePosition.fromSlug("elbow"))
        assertNull(NodePosition.fromSlug(null))
    }
}

package com.physdaq.mobile.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class BodyMapLayoutTest {

    // The size the app draws at: 300 dp tall. Units here are dp.
    private val h = 300f
    private val w = h * BodyMapLayout.ASPECT
    private val tapRadius = 22f

    @Test
    fun everyPositionHasADot() {
        assertEquals(NodePosition.values().toSet(), BodyMapLayout.dots.keys)
    }

    @Test
    fun tappingADotCentreHitsThatDot() {
        for ((pos, p) in BodyMapLayout.dots) {
            assertEquals(pos, BodyMapLayout.hit(p.x * w, p.y * h, w, h, tapRadius))
        }
    }

    @Test
    fun dotsAreFarEnoughApartToTapReliably() {
        // 9 dp dots: at least 22 dp between centres so neighbours never overlap.
        val list = BodyMapLayout.dots.values.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val d = hypot((list[i].x - list[j].x) * w, (list[i].y - list[j].y) * h)
            assertTrue("dots $i and $j only ${d}dp apart", d >= 22f)
        }
    }

    @Test
    fun theNearestDotWins() {
        val wrist = BodyMapLayout.dots.getValue(NodePosition.WRIST_LEFT)
        val finger = BodyMapLayout.dots.getValue(NodePosition.FINGER_LEFT)
        // a third of the way from wrist toward finger
        val x = (wrist.x + (finger.x - wrist.x) / 3) * w
        val y = (wrist.y + (finger.y - wrist.y) / 3) * h
        assertEquals(NodePosition.WRIST_LEFT, BodyMapLayout.hit(x, y, w, h, tapRadius))
    }

    @Test
    fun aTapFarFromEveryDotHitsNothing() {
        assertNull(BodyMapLayout.hit(2f, h * 0.75f, w, h, tapRadius))
    }

    @Test
    fun frontViewPutsThePersonsLeftOnTheScreensRight() {
        val d = BodyMapLayout.dots
        assertTrue(d.getValue(NodePosition.WRIST_LEFT).x > 0.5f)
        assertTrue(d.getValue(NodePosition.WRIST_RIGHT).x < 0.5f)
        assertTrue(d.getValue(NodePosition.ANKLE_LEFT).x > 0.5f)
    }

    @Test
    fun allPointsAreInsideTheDrawingArea() {
        val all = BodyMapLayout.dots.values + BodyMapLayout.limbs.flatten()
        for (p in all) assertTrue("$p", p.x in 0f..1f && p.y in 0f..1f)
    }
}

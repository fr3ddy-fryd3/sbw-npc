package com.sbwnpc.squad.combat

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class FollowFormationTest {
    private val player = Point3(100.0, 70.0, 100.0)

    private fun distance(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)

    @Test fun `a standing player is surrounded, not led`() {
        val slots = (0 until 8).map { FollowFormation.slot(player, null, it, moving = false) }
        slots.forEach { assertTrue(distance(it, player) in 3.0..9.0, "Close, but not on him: $it") }
    }

    @Test fun `a walking player is led along his heading`() {
        val ahead = (0 until 8).map { FollowFormation.slot(player, 1.0 to 0.0, it, moving = true) }
        assertTrue(ahead.map { it.x }.average() > player.x + FollowFormation.LEAD * 0.8, "The squad's middle is ahead of him")
    }

    @Test fun `men stand apart from each other`() {
        val slots = (0 until 8).map { FollowFormation.slot(player, 0.0 to 1.0, it, moving = true) }
        for (a in slots.indices) for (b in 0 until a) assertTrue(distance(slots[a], slots[b]) >= 1.5, "$a and $b overlap")
    }

    @Test fun `a slot does not turn with the heading`() {
        val east = FollowFormation.slot(player, 1.0 to 0.0, 3, moving = true)
        val north = FollowFormation.slot(player, 0.0 to 1.0, 3, moving = true)
        // Only the lead moves; the spread round it is the same.
        assertEquals(east.x - FollowFormation.LEAD, north.x, 1e-9)
        assertEquals(east.z, north.z - FollowFormation.LEAD, 1e-9)
    }

    @Test fun `near is a horizontal distance`() {
        assertTrue(FollowFormation.near(Point3(100.0, 10.0, 100.0), player))
        assertFalse(FollowFormation.near(Point3(100.0 + FollowFormation.NEAR_DISTANCE + 1, 70.0, 100.0), player))
    }
}

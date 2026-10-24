package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class FireSlotSightTest {
    private val enemy = Point3(0.0, 0.0, 0.0)
    private val slot = Point3(0.0, 0.0, 56.0)

    private fun distance(point: Point3) = hypot(point.x - enemy.x, point.z - enemy.z)

    @Test fun `a slot that sees the defence stays where it is`() {
        assertEquals(FireSlotSight.Sighted(slot), FireSlotSight.choose(slot, listOf(enemy), 48.0) { _, _ -> true })
    }

    @Test fun `a slot behind a ridge moves to a nearer point that sees the defence`() {
        val moved = FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ -> distance(post) < 54 }!!.post
        assertTrue(distance(moved) in 50.0..54.0, "Stays in the band between clearance and the line: ${distance(moved)}")
    }

    @Test fun `a slot in a hollow moves sideways when only the flank sees`() {
        val moved = FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ -> post.x >= 6.0 }!!.post
        assertEquals(9.0, moved.x, 1e-9)
        assertTrue(distance(moved) >= 50.0, "Still outside the clearance")
    }

    @Test fun `no candidate sees the defence`() {
        assertNull(FireSlotSight.choose(slot, listOf(enemy), 48.0) { _, _ -> false })
    }

    @Test fun `no candidate comes nearer than the blind minimum`() {
        val tried = ArrayList<Point3>()
        FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ ->
            tried += post
            false
        }
        assertTrue(tried.isNotEmpty() && tried.all { distance(it) >= FireSlotSight.BLIND_MIN_DISTANCE - 1e-9 })
    }

    @Test fun `the band is searched before anything farther or nearer`() {
        val tried = ArrayList<Point3>()
        FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ ->
            tried += post
            false
        }
        val firstOutside = tried.indexOfFirst { distance(it) > 56.0 + 1e-9 || distance(it) < 50.0 - 1e-9 }
        assertTrue(firstOutside > 0 && tried.take(firstOutside).all { kotlin.math.abs(it.x) <= 18.0 + 1e-9 })
    }

    @Test fun `a slot in a hollow moves back before it comes nearer`() {
        val moved = FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ -> distance(post) >= 70.0 || distance(post) <= 36.0 }!!
        assertTrue(distance(moved.post) >= 70.0, "Moved back: ${distance(moved.post)}")
        assertNull(moved.clearance, "Outside the clearance, the role's own stays")
    }

    @Test fun `a slot blind everywhere outside the clearance comes nearer with that clearance`() {
        val moved = FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ -> distance(post) <= 43.0 }!!
        assertTrue(distance(moved.post) in FireSlotSight.BLIND_MIN_DISTANCE..43.0, "Nearer: ${distance(moved.post)}")
        assertTrue(moved.clearance!! <= distance(moved.post), "The man may stand there: ${moved.clearance}")
    }

    @Test fun `a slot that sees a defender beside the nearest one stays where it is`() {
        val beside = Point3(8.0, 0.0, 0.0)
        assertEquals(FireSlotSight.Sighted(slot), FireSlotSight.choose(slot, listOf(enemy, beside), 48.0) { _, target -> target == beside })
    }

    @Test fun `the nearest defender is the one looked at`() {
        val near = Point3(0.0, 0.0, 20.0)
        FireSlotSight.choose(Point3(0.0, 0.0, 76.0), listOf(enemy, near), 48.0) { _, target ->
            assertEquals(near, target)
            true
        }
    }

    @Test fun `a found post is kept until the slot moves, a miss is searched again soon`() {
        assertTrue(FireSlotSight.stillAnswers(moved = 3.0, found = true, age = 10_000))
        assertFalse(FireSlotSight.stillAnswers(moved = 5.0, found = true, age = 0))
        assertTrue(FireSlotSight.stillAnswers(moved = 0.0, found = false, age = 39))
        assertFalse(FireSlotSight.stillAnswers(moved = 0.0, found = false, age = 40))
    }
}

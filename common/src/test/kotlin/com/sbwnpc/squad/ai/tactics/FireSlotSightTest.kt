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
        assertEquals(slot, FireSlotSight.choose(slot, listOf(enemy), 48.0) { _, _ -> true })
    }

    @Test fun `a slot behind a ridge moves to a nearer point that sees the defence`() {
        val moved = FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ -> distance(post) < 54 }!!
        assertTrue(distance(moved) in 50.0..54.0, "Stays in the band between clearance and the line: ${distance(moved)}")
    }

    @Test fun `a slot in a hollow moves sideways when only the flank sees`() {
        val moved = FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ -> post.x >= 6.0 }!!
        assertEquals(6.0, moved.x, 1e-9)
        assertTrue(distance(moved) >= 50.0, "Still outside the clearance")
    }

    @Test fun `no candidate sees the defence`() {
        assertNull(FireSlotSight.choose(slot, listOf(enemy), 48.0) { _, _ -> false })
    }

    @Test fun `no candidate comes inside the clearance`() {
        val tried = ArrayList<Point3>()
        FireSlotSight.choose(slot, listOf(enemy), 48.0) { post, _ ->
            tried += post
            false
        }
        assertTrue(tried.isNotEmpty() && tried.all { distance(it) >= 50.0 - 1e-9 })
    }

    @Test fun `the nearest defender is the one looked at`() {
        val near = Point3(0.0, 0.0, 20.0)
        FireSlotSight.choose(Point3(0.0, 0.0, 76.0), listOf(enemy, near), 48.0) { _, target ->
            assertEquals(near, target)
            true
        }
    }
}

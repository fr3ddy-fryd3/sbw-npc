package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GroundRouteProgressTest {
    private fun at(x: Double, z: Double = 0.0) = Vec3(x, 64.0, z)

    @Test
    fun `finishing a search does not steer back to its obsolete starting position`() {
        val route = GroundRouteProgress()
        route.reset(listOf(at(0.0), at(30.0), at(60.0), at(120.0)), at(52.0))
        assertEquals(at(67.0), route.waypoint(at(52.0), 15.0))
        assertEquals(at(87.0), route.waypoint(at(72.0), 15.0))
        assertEquals(at(87.0), route.waypoint(at(60.0), 15.0))
    }

    @Test
    fun `a hairpin progresses along the route even as it gets farther from the goal`() {
        val route = GroundRouteProgress()
        route.reset(listOf(at(0.0), at(80.0), at(80.0, 40.0), at(0.0, 40.0)), at(60.0))
        val remaining = route.remaining
        assertEquals(at(80.0, 10.0), route.waypoint(at(75.0), 15.0))
        assertTrue(route.remaining < remaining)
        assertEquals(at(65.0, 40.0), route.waypoint(at(80.0, 40.0), 15.0))
    }

    @Test
    fun `passing an unfinished endpoint means wait rather than turn back`() {
        val route = GroundRouteProgress()
        route.reset(listOf(at(0.0), at(30.0)), at(50.0))
        route.waypoint(at(50.0), 15.0)
        assertTrue(route.finished(at(50.0), 6.0))
        assertFalse(route.finished(at(20.0), 6.0))
    }

    @Test
    fun `repeated one node refreshes preserve the route the vehicle is still driving`() {
        val route = GroundRouteProgress()
        route.reset(listOf(at(0.0), at(40.0), at(80.0), at(120.0)), at(30.0))
        for (x in 30..90) {
            val here = at(x.toDouble())
            assertFalse(route.replaceIfAdvancing(listOf(here), here, 6.0))
            assertFalse(route.replaceIfAdvancing(emptyList(), here, 6.0))
            assertEquals(at(x + 15.0), route.waypoint(here, 15.0))
        }
    }

    @Test
    fun `a refreshed route already behind the hull cannot replace its continuation`() {
        val route = GroundRouteProgress()
        route.reset(listOf(at(0.0), at(60.0), at(120.0)), at(52.0))
        assertFalse(route.replaceIfAdvancing(listOf(at(0.0), at(30.0)), at(52.0), 6.0))
        assertEquals(at(67.0), route.waypoint(at(52.0), 15.0))
    }

    @Test
    fun `a useful refreshed detour takes over a blocked route`() {
        val route = GroundRouteProgress()
        route.reset(listOf(at(0.0), at(120.0)), at(40.0))
        assertTrue(route.replaceIfAdvancing(listOf(at(40.0), at(40.0, 30.0), at(120.0, 30.0)), at(40.0), 6.0))
        assertEquals(at(40.0, 15.0), route.waypoint(at(40.0), 15.0))
    }
}

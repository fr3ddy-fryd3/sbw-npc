package com.sbwnpc.squad.route

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DrivingTest {
    private fun at(x: Double, z: Double = 0.5) = Vec3(x, 64.0, z)

    private open class Plain : Ground {
        override fun known(x: Int, z: Int) = true
        override fun kind(x: Int, z: Int) = GroundMap.Kind.GROUND
        override fun height(x: Int, z: Int) = 64
        override fun room(x: Int, z: Int) = 4
    }

    private val river = object : Plain() {
        override fun kind(x: Int, z: Int) = if (x in 40..99) GroundMap.Kind.WATER else GroundMap.Kind.GROUND
    }

    private fun plan(ground: Ground, goal: Vec3, amphibious: Boolean, from: Vec3 = at(0.5)): CellPlanner.Route {
        val search = CellPlanner.search(Driving(ground, 1.5, 2.25, 2.5, amphibious), from, goal)!!
        var ticks = 0
        while (!search.step(2500)) assertTrue(++ticks < 2000, "search did not finish")
        return search.result() ?: error("no route: ${search.stoppedBy}")
    }

    @Test
    fun `an amphibious hull crosses a river and only disembarks on the far bank`() {
        val found = plan(river, at(180.5), true)
        assertTrue(found.complete)
        assertTrue(found.landing.x >= 175.0, "amphibious crew left the vehicle before crossing")
        assertTrue(found.trail.any { it.x in 40.0..99.9 })
        assertNotEquals(GroundMap.Kind.WATER, river.kind(found.landing.x.toInt(), found.landing.z.toInt()))
        val longTrip = plan(river, at(2200.5), true)
        assertFalse(longTrip.complete, "crossing a river must not end a two kilometre trip")
        assertTrue(longTrip.landing.x >= 700.0)
    }

    @Test
    fun `a non amphibious hull stops on the near bank so the crew can walk on`() {
        val found = plan(river, at(180.5), false)
        assertTrue(found.complete)
        assertTrue(found.landing.x in 30.0..40.0)
        assertTrue(found.trail.none { river.kind(it.x.toInt(), it.z.toInt()) == GroundMap.Kind.WATER })
        val longTrip = plan(river, at(2200.5), false)
        assertTrue(longTrip.complete, "a non amphibious crew should still leave at an impassable bank")
        assertTrue(longTrip.landing.x in 30.0..40.0)
    }

    @Test
    fun `an unknown frontier behind the vehicle does not send it back to the start of the march`() {
        // At the bank, the only unknown ground is back toward the deployment point. An optimistic
        // drive through that unknown ground used to beat the foot route and send the hull back.
        val shore = object : Plain() {
            override fun known(x: Int, z: Int) = x >= -12
            override fun kind(x: Int, z: Int) = if (x >= 40) GroundMap.Kind.WATER else GroundMap.Kind.GROUND
        }
        val found = plan(shore, at(180.5), false, at(32.5))
        assertTrue(found.complete, "the crew should get off at this bank rather than explore behind it")
        assertTrue(found.landing.x >= 32.0, "route returned toward deployment: ${found.landing}")
    }

    @Test
    fun `a known road detour can first move away from the goal`() {
        val island = object : Plain() {
            override fun kind(x: Int, z: Int) =
                if (x in 40..99 && z in -60..60) GroundMap.Kind.WATER else GroundMap.Kind.GROUND
        }
        val found = plan(island, at(180.5), false)
        assertTrue(found.complete)
        assertTrue(found.landing.x >= 175.0, "a known road around the water should still be used")
        assertTrue(found.trail.any { kotlin.math.abs(it.z) > 60.0 })
    }

    @Test
    fun `a longer usable drive wins over leaving the crew hundreds of blocks short`() {
        val ridge = object : Plain() {
            override fun height(x: Int, z: Int) = if (x in 100..119 && z in -300..300) 100 else 64
        }
        // The drive round this ridge costs more than a straight-line estimate of walking from
        // its foot. That estimate used to end the trip over 500 blocks before the objective.
        val found = plan(ridge, at(620.5), true, at(80.5))
        assertTrue(found.complete)
        assertTrue(found.landing.distanceTo(at(620.5)) <= 20.0, "a usable detour was abandoned: ${found.landing}")
        assertTrue(found.trail.any { kotlin.math.abs(it.z) > 300.0 })
    }

    @Test
    fun `a longer detour to unseen ground continues instead of disembarking`() {
        val ridge = object : Plain() {
            override fun known(x: Int, z: Int) = x in -320..159 && z in -400..400
            override fun height(x: Int, z: Int) = if (x in 100..119 && z in -300..300) 100 else 64
        }
        val found = plan(ridge, at(620.5), true, at(80.5))
        assertFalse(found.complete, "the known detour leads to a continuation, not a foot exit")
        assertTrue(found.landing.x >= 150.0, "the detour did not get past the ridge: ${found.landing}")
        assertTrue(found.trail.any { kotlin.math.abs(it.z) > 300.0 })
    }

    @Test
    fun `a two kilometre drive over known ground continues beyond the search range`() {
        val found = plan(Plain(), at(2200.5), false)
        assertFalse(found.complete, "the range limit must not be a disembarkation point")
        assertTrue(found.landing.x >= 700.0)
        val next = plan(Plain(), at(2200.5), false, found.landing)
        assertTrue(next.landing.x > found.landing.x + 600.0)
    }

    @Test
    fun `water with an overhead obstruction or a steep far bank remains impassable`() {
        val canopy = object : Plain() {
            override fun kind(x: Int, z: Int) = river.kind(x, z)
            override fun room(x: Int, z: Int) = if (x in 40..99) 1 else 4
        }
        assertTrue(plan(canopy, at(180.5), true).landing.x < 40.0)
        val cliff = object : Plain() {
            override fun kind(x: Int, z: Int) = river.kind(x, z)
            override fun height(x: Int, z: Int) = if (x >= 100) 90 else 64
        }
        val found = plan(cliff, Vec3(180.5, 90.0, 0.5), true)
        assertTrue(found.complete)
        assertTrue(found.landing.x < 40.0, "the crew cannot be dropped in deep water below a cliff")
    }
}

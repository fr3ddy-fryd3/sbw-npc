package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.route.CellPlanner
import com.sbwnpc.squad.route.Ground
import com.sbwnpc.squad.route.GroundMap
import com.sbwnpc.squad.route.Walking
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SquadMarchTest {
    private val longRoute = (0..1500).map { BlockPos(it, 64, 0) }

    @Test
    fun `every slot of a 32 member squad keeps advancing even with a stationary lead`() {
        for (order in listOf(SquadOrder.ATTACK, SquadOrder.MOVE, SquadOrder.PATROL)) {
            for (slot in 0 until 32) {
                val progress = MarchProgress(2000).apply { append(longRoute) }
                val walker = UUID.randomUUID()
                val local = SquadFormation.transitOffset(order, slot, 32)
                var position = Vec3(60.5, 64.5, local.x)
                repeat(60) {
                    val here = progress.index(walker, position)
                    val anchor = SquadMarch.formationAnchorIndex(progress.route, 0, here, local.z)
                    // On this straight +X route, depth is X and sideways offset is Z.
                    val next = Vec3(progress.route[anchor].x + 0.5 + local.z, 64.5, local.x)
                    assertTrue(next.x >= position.x + 14.0,
                        "$order slot $slot stopped at $position aiming at $next (depth ${local.z})")
                    position = position.add(1.0, 0.0, 0.0)
                }
            }
        }
    }

    @Test
    fun `a following member still holds its place behind an advancing lead`() {
        val local = SquadFormation.transitOffset(SquadOrder.ATTACK, 2, 32)
        val anchor = SquadMarch.formationAnchorIndex(longRoute, 100, 80, local.z)
        assertEquals(116, anchor)
        assertEquals(113.0, longRoute[anchor].x + local.z)
    }

    @Test
    fun `rear depth is measured in blocks on diagonal trails`() {
        val diagonal = (0..1500).map { BlockPos(it, 64, it) }
        val local = SquadFormation.transitOffset(SquadOrder.ATTACK, 31, 32)
        val anchor = SquadMarch.formationAnchorIndex(diagonal, 0, 60, local.z)
        val depth = (anchor - 60) * Math.sqrt(2.0) + local.z
        assertTrue(depth >= 16.0 * Math.sqrt(2.0))
        assertTrue(depth < 17.0 * Math.sqrt(2.0))
    }

    @Test
    fun `formation anchors stay within the route near the destination`() {
        val short = longRoute.take(100)
        assertEquals(short.lastIndex, SquadMarch.formationAnchorIndex(short, 90, 95, -48.0))
    }

    @Test
    fun `the rear reaches the end of a partial route instead of stopping 48 blocks short`() {
        val partial = longRoute.take(160)
        val progress = MarchProgress(2000).apply { append(partial) }
        val walker = UUID.randomUUID()
        val local = SquadFormation.transitOffset(SquadOrder.ATTACK, 31, 32)
        var position = Vec3(90.5, 64.5, local.x)
        repeat(68) {
            val here = progress.index(walker, position)
            val anchor = SquadMarch.formationAnchorIndex(partial, 0, here, local.z)
            val slot = Vec3(partial[anchor].x + 0.5 + local.z, 64.5, local.x)
            val next = if (SquadMarch.formationAdvances(partial, here, slot)) slot
                else Vec3.atBottomCenterOf(partial[(here + 16).coerceAtMost(partial.lastIndex)])
            assertTrue(next.x > position.x, "rear stopped at $position before the continuation point")
            position = position.add(1.0, 0.0, 0.0)
        }
        assertTrue(progress.index(walker, position) >= partial.size - 8)
    }

    @Test
    fun `a known surface endpoint at the foot of a cliff is not the walking goal`() {
        val ground = object : Ground {
            override fun known(x: Int, z: Int) = true
            override fun kind(x: Int, z: Int) = GroundMap.Kind.GROUND
            override fun height(x: Int, z: Int) = if (x < 40) 64 else 100
            override fun room(x: Int, z: Int) = 4
        }
        val goal = BlockPos(150, 100, 0)
        val search = CellPlanner.search(Walking(ground), Vec3(0.5, 64.0, 0.5), Vec3.atBottomCenterOf(goal))!!
        var ticks = 0
        while (!search.step(2500)) assertTrue(++ticks < 1000)
        val found = search.result()!!
        assertTrue(found.complete, "the planner knows all the land it chose")
        assertTrue(found.landing.x < 40, "the surface route cannot step up the cliff")
        assertFalse(SquadMarch.reachedWalkingGoal(found, goal), "the march must continue with local 3D paths")
    }

    @Test
    fun `a route across an open plain does reach the walking goal`() {
        val ground = object : Ground {
            override fun known(x: Int, z: Int) = true
            override fun kind(x: Int, z: Int) = GroundMap.Kind.GROUND
            override fun height(x: Int, z: Int) = 64
            override fun room(x: Int, z: Int) = 4
        }
        val goal = BlockPos(150, 64, 0)
        val search = CellPlanner.search(Walking(ground), Vec3(0.5, 64.0, 0.5), Vec3.atBottomCenterOf(goal))!!
        var ticks = 0
        while (!search.step(2500)) assertTrue(++ticks < 1000)
        assertTrue(SquadMarch.reachedWalkingGoal(search.result()!!, goal))
    }
}

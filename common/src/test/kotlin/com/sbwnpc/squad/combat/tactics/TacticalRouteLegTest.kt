package com.sbwnpc.squad.combat.tactics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TacticalRouteLegTest {
    private val from = Vec3(90.5, 63.0, 12.5)
    private val anchor = Vec3(40.5, 63.0, 12.5)
    private val target = BlockPos(40, 63, 12)

    private fun path(reached: Boolean, vararg xs: Int) = Path(xs.map { Node(it, 63, 12) }, target, reached)

    @Test fun `a path that reaches the anchor is the route`() {
        val route = TacticalRoutes.route(path(true, 90, 70, 50, 40), from, anchor)
        assertEquals(4, route?.size)
    }

    @Test fun `a partial path that ends where the man stands is no route`() {
        assertNull(TacticalRoutes.route(path(false, 90, 89, 91), from, anchor))
    }

    @Test fun `a partial path that gets a leg nearer still leads the way`() {
        assertNotNull(TacticalRoutes.route(path(false, 90, 80, 68), from, anchor))
    }

    @Test fun `a partial path round an obstacle that gains a few blocks still leads the way`() {
        // Along a wall and round its end: 8 blocks nearer, far less than a leg (#74).
        assertNotNull(TacticalRoutes.route(path(false, 90, 90, 88, 85, 82), from, anchor))
    }

    @Test fun `a partial path that gains almost nothing is no route`() {
        assertNull(TacticalRoutes.route(path(false, 90, 88, 86), from, anchor))
    }

    @Test fun `a partial route walked to its end is used up, a full one is not`() {
        val partial = listOf(Vec3(90.5, 63.0, 12.5), Vec3(70.5, 63.0, 12.5))
        assertTrue(TacticalRoutes.walkedOut(partial, Vec3(71.0, 63.0, 12.5), anchor))
        assertFalse(TacticalRoutes.walkedOut(partial, from, anchor))
        val full = listOf(from, anchor)
        assertFalse(TacticalRoutes.walkedOut(full, Vec3(41.0, 63.0, 12.5), anchor))
    }

    @Test fun `a short route round an obstacle is not used up before it is walked`() {
        // Out along the wall and back in: its end is 7 blocks from where he starts.
        val round = listOf(from, Vec3(90.5, 63.0, 22.5), Vec3(83.5, 63.0, 22.5), Vec3(83.5, 63.0, 12.5))
        assertFalse(TacticalRoutes.walkedOut(round, from, anchor))
        assertFalse(TacticalRoutes.walkedOut(round, Vec3(88.0, 63.0, 22.5), anchor))
        assertTrue(TacticalRoutes.walkedOut(round, Vec3(83.5, 63.0, 13.5), anchor))
    }

    @Test fun `a path of a single node is no route`() {
        assertNull(TacticalRoutes.route(path(true, 90), from, anchor))
    }
}

package com.sbwnpc.squad.combat.tactics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test fun `a path of a single node is no route`() {
        assertNull(TacticalRoutes.route(path(true, 90), from, anchor))
    }
}

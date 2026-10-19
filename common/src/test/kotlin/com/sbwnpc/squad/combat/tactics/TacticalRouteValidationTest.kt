package com.sbwnpc.squad.combat.tactics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalRouteValidationTest {
    @Test fun `a built route is kept for one second`() {
        val validation = TacticalRouteValidation(Path((0..8).map { Node(it, 64, 0) }, BlockPos(8, 64, 0), true), 100)
        assertTrue(validation.fresh(120))
        assertFalse(validation.fresh(121))
    }
}

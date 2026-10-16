package com.sbwnpc.squad.combat.tactics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalRouteValidationTest {
    @Test fun `a deferred route resumes at its unvalidated node and rejects a later hazard`() {
        val path = Path((0..8).map { Node(it, 64, 0) }, BlockPos(8, 64, 0), true)
        val validation = TacticalRouteValidation(path, 100)
        val checked = mutableListOf<Int>()
        assertEquals(
            TacticalRouteValidation.Outcome.DEFERRED,
            validation.resume {
                if (it == 4) {
                    TacticalRouteValidation.Outcome.DEFERRED
                } else {
                    checked += it
                    TacticalRouteValidation.Outcome.SAFE
                }
            },
        )
        assertEquals(4, validation.nextNode)
        assertSame(path, validation.path)
        assertEquals(
            TacticalRouteValidation.Outcome.UNSAFE,
            validation.resume {
                checked += it
                if (it == 6) TacticalRouteValidation.Outcome.UNSAFE else TacticalRouteValidation.Outcome.SAFE
            },
        )
        assertEquals((0..6).toList(), checked)
        assertTrue(validation.fresh(120))
        assertFalse(validation.fresh(121))
    }
}

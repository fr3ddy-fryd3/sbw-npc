package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The transport threshold belongs to the group, not the member closest to its objective. */
class SquadTransitTest {
    @Test
    fun `a member less than one hundred blocks away travels with the distant squad`() {
        val positions = listOf(32.5, 35.5, 38.5, 41.5, 68.5).map { Vec3(it, 1.0, 20.5) }
        assertTrue(SquadTransit.distant(positions, Vec3(150.5, 1.0, 20.5)))
    }

    @Test
    fun `a distant straggler does not put an already arrived squad back into transit`() {
        val positions = listOf(10.0, 130.0, 135.0, 140.0, 145.0).map { Vec3(it, 1.0, 20.0) }
        assertFalse(SquadTransit.distant(positions, Vec3(150.0, 1.0, 20.0)))
    }

    @Test
    fun `the one hundred block boundary and an empty squad are not distant transit`() {
        assertFalse(SquadTransit.distant(listOf(Vec3.ZERO), Vec3(100.0, 0.0, 0.0)))
        assertTrue(SquadTransit.distant(listOf(Vec3.ZERO), Vec3(101.0, 0.0, 0.0)))
        assertFalse(SquadTransit.distant(emptyList(), Vec3(150.0, 0.0, 0.0)))
    }
}

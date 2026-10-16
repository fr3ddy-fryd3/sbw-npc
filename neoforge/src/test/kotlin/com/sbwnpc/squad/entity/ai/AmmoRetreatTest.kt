package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AmmoRetreatTest {
    private val origin = Vec3(0.5, 64.0, 0.5)
    private val squad = UUID(0, 1)

    @Test fun `reacquiring a relayed target cannot renew distance or time allowance`() {
        val retreat = AmmoRetreat()
        assertTrue(retreat.begin(origin, 0, squad, 1))
        assertTrue(retreat.permits(origin.add(32.0, 0.0, 0.0)))
        assertFalse(retreat.begin(origin.add(24.0, 0.0, 0.0), 100, squad, 1))
        assertEquals(origin, retreat.origin)
        assertFalse(retreat.permits(origin.add(33.0, 0.0, 0.0)))
        assertTrue(retreat.exhausted(origin.add(30.0, 0.0, 0.0), 100))
        assertTrue(retreat.exhausted(origin, 200))
    }

    @Test fun `taking cover holds the episode until ammunition or a new order permits movement`() {
        val retreat = AmmoRetreat()
        retreat.begin(origin, 0, squad, 1)
        retreat.hold()
        assertFalse(retreat.begin(origin.add(16.0, 0.0, 0.0), 50, squad, 1))
        assertTrue(retreat.exhausted(origin, 50))
        assertTrue(retreat.begin(origin, 60, squad, 2))
        assertFalse(retreat.holding)
        retreat.reset()
        assertTrue(retreat.begin(origin.add(100.0, 0.0, 0.0), 500, squad, 2))
        assertEquals(origin.add(100.0, 0.0, 0.0), retreat.origin)
    }
}

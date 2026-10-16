package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FiringSpotOwnershipTest {
    @Test fun `old execution cleanup cannot release a newer post for the same NPC`() {
        val npc = UUID.randomUUID()
        val old = UUID.randomUUID()
        val current = UUID.randomUUID()
        val position = Vec3(10.0, 1.0, 10.0)
        try {
            FiringSpots.claim(npc, Vec3.ZERO, old)
            FiringSpots.claim(npc, position, current)
            FiringSpots.release(npc, old)
            assertTrue(position in FiringSpots.nearby(position, 1.0, UUID.randomUUID()))
            FiringSpots.release(npc, current)
            assertFalse(position in FiringSpots.nearby(position, 1.0, UUID.randomUUID()))
        } finally {
            FiringSpots.release(npc)
        }
    }
}

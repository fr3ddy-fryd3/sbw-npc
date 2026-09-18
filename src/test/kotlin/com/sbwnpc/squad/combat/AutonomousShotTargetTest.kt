package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AutonomousShotTargetTest {
    @Test
    fun `manual shot at identical coordinates never becomes an autonomous shot`() {
        val manual: Vec3 = Vec3(20.0, 30.0, 40.0)
        val target = UUID.randomUUID()
        val queued: Vec3 = AutonomousShotTarget(manual, target, true)

        assertEquals(manual, queued)
        assertFalse(manual is AutonomousShotTarget)
        assertTrue(queued is AutonomousShotTarget)
        assertEquals(target, (queued as AutonomousShotTarget).targetId)
        assertTrue(queued.droneTarget)
        // The marker is stripped before the original gun logic receives its target position.
        assertEquals(manual, queued.position())
        assertFalse(queued.position() is AutonomousShotTarget)
    }
}

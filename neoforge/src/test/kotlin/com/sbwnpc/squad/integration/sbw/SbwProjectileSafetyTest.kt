package com.sbwnpc.squad.integration.sbw

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SbwProjectileSafetyTest {
    @Test fun `safety includes rewound collision even when the current body has left the bullet lane`() {
        val body = AABB(2.0, 0.0, 1.0, 2.6, 1.95, 1.6)
        val motion = Vec3(0.0, 0.0, 0.25)
        val from = Vec3.ZERO.add(0.0, 1.74, 0.0)
        val to = Vec3(80.0, 1.74, 0.0)
        assertTrue(body.clip(from, to).isEmpty)
        val safe = SbwProjectileSafety.envelope(body, motion, motion, -5.0, 3.0)
        assertTrue(safe.clip(from, to).isPresent)
        assertTrue(safe.minZ < body.minZ)
        assertTrue(safe.maxZ > body.maxZ)
    }
}

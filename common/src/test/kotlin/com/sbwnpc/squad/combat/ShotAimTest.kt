package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShotAimTest {
    @Test fun `direct rifle aim includes target height`() {
        val direction = ShotAim.direction(Vec3.ZERO, Vec3(3.0, 4.0, 0.0))
        assertEquals(0.6, direction.x, 1.0e-9)
        assertEquals(0.8, direction.y, 1.0e-9)
        assertEquals(1.0, direction.length(), 1.0e-9)
    }

    @Test fun `launcher pitch changes elevation without losing target azimuth`() {
        val direction = ShotAim.direction(Vec3.ZERO, Vec3(0.0, 0.0, -10.0), -30.0f)
        assertEquals(0.0, direction.x, 1.0e-9)
        assertEquals(0.5, direction.y, 1.0e-9)
        assertEquals(-kotlin.math.sqrt(3.0) / 2.0, direction.z, 1.0e-9)
    }
}

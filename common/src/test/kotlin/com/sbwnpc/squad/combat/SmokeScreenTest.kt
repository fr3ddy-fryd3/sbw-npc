package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SmokeScreenTest {
    @Test fun `the screen is aimed at the enemy on the level`() {
        val point = SmokeScreen.screenPoint(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 80.0, 0.0))!!
        assertEquals(10.0, point.x, 1e-9)
        assertEquals(64.0, point.y, 1e-9)
        assertEquals(0.0, point.z, 1e-9)
    }

    @Test fun `an enemy straight above or below gives no direction`() {
        assertNull(SmokeScreen.screenPoint(Vec3(0.0, 64.0, 0.0), Vec3(0.2, 90.0, 0.3)))
        assertNotNull(SmokeScreen.screenPoint(Vec3(0.0, 64.0, 0.0), Vec3(5.0, 64.0, 0.0)))
    }
}

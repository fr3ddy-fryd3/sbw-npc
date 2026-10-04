package com.sbwnpc.squad.integration.sbw

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SbwGrenadesTest {
    @Test
    fun `ordinary nearby target has a solution at the actual throw speed`() {
        val velocity = SbwGrenades.throwVelocity(Vec3(0.0, 1.5, 0.0), Vec3(12.0, 0.9, 0.0), Vec3.ZERO)
        assertNotNull(velocity)
        assertEquals(1.0, velocity!!.length(), 0.01)
    }

    @Test
    fun `nearby target above the ballistic ceiling cannot be reached`() {
        assertNull(SbwGrenades.throwVelocity(Vec3.ZERO, Vec3(10.0, 10.0, 0.0), Vec3.ZERO))
    }

    @Test
    fun `a target escaping faster than the grenade has no valid throw`() {
        assertNull(SbwGrenades.throwVelocity(Vec3.ZERO, Vec3(12.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0)))
    }
}

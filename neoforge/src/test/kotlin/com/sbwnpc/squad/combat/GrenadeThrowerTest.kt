package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GrenadeThrowerTest {
    @Test
    fun `grenades cannot be spent on a distant suppressing threat`() {
        val from = Vec3(0.0, 64.0, 0.0)
        assertTrue(GrenadeThrower.inRange(from, from.add(12.0, 0.0, 0.0)))
        assertFalse(GrenadeThrower.inRange(from, from.add(48.0, 0.0, 0.0)))
        assertFalse(GrenadeThrower.inRange(from, from.add(12.0, 12.0, 0.0)))
        assertFalse(GrenadeThrower.inRange(from, from.add(3.0, 0.0, 0.0)))
    }

    @Test
    fun `target leaving reach between preparation and release invalidates the throw`() {
        val from = Vec3.ZERO
        val target = Vec3(15.0, 0.0, 0.0)
        assertTrue(GrenadeThrower.inRange(from, target))
        assertFalse(GrenadeThrower.inRange(from, target.add(2.0, 0.0, 0.0)))
    }
}

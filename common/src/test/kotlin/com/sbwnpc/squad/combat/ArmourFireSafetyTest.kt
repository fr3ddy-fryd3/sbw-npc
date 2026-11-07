package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Armour beyond an infantry aim must not forbid that infantry's grenade or blind-fire opportunity. */
class ArmourFireSafetyTest {
    @Test fun `distant armour behind infantry is outside the finite segment`() {
        assertFalse(ArmourFireSafety.contains(Vec3.ZERO, Vec3(20.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)))
        assertTrue(ArmourFireSafety.contains(Vec3.ZERO, Vec3(20.0, 0.0, 0.0), Vec3(20.0, 0.0, 0.0)))
        assertTrue(ArmourFireSafety.contains(Vec3.ZERO, Vec3(20.0, 0.0, 0.0), Vec3(23.0, 0.0, 0.0)))
        assertFalse(ArmourFireSafety.contains(Vec3.ZERO, Vec3(20.0, 0.0, 0.0), Vec3(-10.0, 0.0, 0.0)))
    }
}

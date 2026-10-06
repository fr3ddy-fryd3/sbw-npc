package com.sbwnpc.squad.combat

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FiringConeTest {
    private val origin = Vec3(0.0, 1.74, 0.0)

    @Test fun `a standing ally's head blocks the shot even when its body centre is below the ray`() {
        val cone = FiringCone(origin, Vec3(80.0, 1.74, 0.0), 0.0)
        assertTrue(cone.intersects(AABB(1.2, 0.0, -0.3, 1.8, 1.95, 0.3)))
        assertFalse(cone.intersects(AABB(1.2, 0.0, 3.7, 1.8, 1.95, 4.3)))
    }

    @Test fun `component spread includes diagonal deviations outside the nominal degree angle`() {
        val cone = FiringCone(origin, Vec3(80.0, 1.74, 0.0), 5.5)
        // At 40 blocks, simultaneous Y/Z deviations allowed by Projectile.shoot
        // produce an angle around 7.6 degrees, greater than the old 5.5 degree cone.
        assertTrue(cone.intersects(AABB(39.9, 5.4, 3.7, 40.1, 5.6, 3.9)))
        assertFalse(cone.intersects(AABB(39.9, 20.0, 20.0, 40.1, 20.2, 20.2)))
        assertFalse(cone.intersects(AABB(89.9, 1.5, -0.1, 90.1, 1.7, 0.1)))
    }
}

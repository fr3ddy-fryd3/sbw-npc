package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.sin
import kotlin.math.cos

class DroneAimTest {
    @Test
    fun `hold fire while turning or when aim direction is undefined`() {
        val target = Vec3(0.0, 0.0, 20.0)
        assertNull(DroneAim.firingEndpoint(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), target))
        assertNull(DroneAim.firingEndpoint(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), target))
        assertNull(DroneAim.firingEndpoint(Vec3.ZERO, Vec3.ZERO, target))
        assertNull(DroneAim.firingEndpoint(target, target.normalize(), target))
    }

    @Test
    fun `friendly fire endpoint follows barrel even when target is slightly off axis`() {
        val angle = Math.toRadians(2.0)
        val target = Vec3(sin(angle) * 40.0, 0.0, cos(angle) * 40.0)
        val endpoint = DroneAim.firingEndpoint(Vec3.ZERO, Vec3(0.0, 0.0, 1.0), target)!!
        assertEquals(0.0, endpoint.x, 1.0e-9)
        assertEquals(40.0, endpoint.z, 1.0e-9)
        assertNotEquals(target, endpoint)
        val outside = Math.toRadians(4.0)
        assertNull(DroneAim.firingEndpoint(Vec3.ZERO, Vec3(0.0, 0.0, 1.0),
            Vec3(sin(outside), 0.0, cos(outside))))
    }

    @Test
    fun `vertical fire preserves origin and target distance`() {
        val origin = Vec3(12.0, 65.6, -3.0)
        val target = origin.add(0.0, 20.0, 0.0)
        assertEquals(target, DroneAim.firingEndpoint(origin, Vec3(0.0, 1.0, 0.0), target))
    }
}

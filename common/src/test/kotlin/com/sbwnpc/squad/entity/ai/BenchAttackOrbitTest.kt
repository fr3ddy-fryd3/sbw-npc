package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BenchAttackOrbitTest {
    @Test
    fun `orbit direction puts the occupied bench on the inside`() {
        val center = Vec3.ZERO
        val position = Vec3(BenchAttackOrbit.RADIUS, BenchAttackOrbit.CLEARANCE, 0.0)
        for ((seat, direction) in listOf(2 to 1, 3 to -1)) {
            val waypoint = BenchAttackOrbit.waypoint(position, center, direction, 0f)
            val yaw = HelicopterFlightController.yawToward(position, waypoint)
            assertTrue(BenchAttackOrbit.canAim(seat, yaw, position, center))
            assertFalse(BenchAttackOrbit.canAim(if (seat == 2) 3 else 2, yaw, position, center))
        }
        assertEquals(-1, BenchAttackOrbit.direction(false, true))
        assertEquals(1, BenchAttackOrbit.direction(true, false))
        assertEquals(1, BenchAttackOrbit.direction(true, true))
    }

    @Test
    fun `orbit carrot stays ahead and cannot engage arrival hover`() {
        for (radius in listOf(0.0, 10.0, 30.0, 40.0, 60.0, 100.0)) {
            val position = Vec3(radius, 80.0, 0.0)
            val waypoint = BenchAttackOrbit.waypoint(position, Vec3.ZERO, 1, 0f)
            assertEquals(BenchAttackOrbit.RADIUS, waypoint.horizontalDistance(), 1e-9)
            assertTrue(HelicopterFlightController.horizontalDistance(position, waypoint) > 6.0)
        }
    }
}

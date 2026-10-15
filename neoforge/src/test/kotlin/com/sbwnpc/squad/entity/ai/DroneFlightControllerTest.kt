package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DroneFlightControllerTest {
    private val origin = Vec3(0.0, 64.0, 0.0)

    @Test
    fun `yaw follows vanilla convention - south is 0, west is 90, north is 180`() {
        assertEquals(0f, DroneFlightController.yawToward(origin, Vec3(0.0, 64.0, 10.0)), 1e-3f)
        assertEquals(90f, DroneFlightController.yawToward(origin, Vec3(-10.0, 64.0, 0.0)), 1e-3f)
        assertEquals(180f, kotlin.math.abs(DroneFlightController.yawToward(origin, Vec3(0.0, 64.0, -10.0))), 1e-3f)
        assertEquals(-90f, DroneFlightController.yawToward(origin, Vec3(10.0, 64.0, 0.0)), 1e-3f)
    }

    @Test
    fun `yaw steps are clamped and take the short way round`() {
        assertEquals(6f, DroneFlightController.stepYaw(0f, 90f), 1e-3f)
        assertEquals(-6f, DroneFlightController.stepYaw(0f, -90f), 1e-3f)
        assertEquals(3f, DroneFlightController.stepYaw(0f, 3f), 1e-3f)
        // 170 -> -170 is a 20 degree turn through 180, not 340 the other way
        assertEquals(176f, DroneFlightController.stepYaw(170f, -170f), 1e-3f)
    }

    @Test
    fun `thrust only when pointed at the target and below cruise speed`() {
        val target = Vec3(0.0, 64.0, 100.0) // straight ahead at yaw 0
        val aligned = DroneFlightController.steer(origin, 0f, 0.2, target, 64.0, 0.9)
        assertTrue(aligned.forward)
        assertFalse(aligned.back)

        val tooFast = DroneFlightController.steer(origin, 0f, 1.5, target, 64.0, 0.9)
        assertFalse(tooFast.forward)
        assertTrue(tooFast.back)

        val facingAway = DroneFlightController.steer(origin, 180f, 0.2, target, 64.0, 0.9)
        assertFalse(facingAway.forward)
        assertEquals(174f, facingAway.yaw, 1e-3f) // turning, 6 degrees per tick
    }

    @Test
    fun `altitude inputs respect the deadband`() {
        val target = Vec3(0.0, 64.0, 100.0)
        assertTrue(DroneFlightController.steer(origin, 0f, 0.0, target, 70.0, 0.9).up)
        assertTrue(DroneFlightController.steer(origin, 0f, 0.0, target, 60.0, 0.9).down)
        val level = DroneFlightController.steer(origin, 0f, 0.0, target, 64.5, 0.9)
        assertFalse(level.up)
        assertFalse(level.down)
    }

    @Test
    fun `cruise altitude clears the highest terrain ahead and never dives`() {
        assertEquals(82.0, DroneFlightController.cruiseAltitude(listOf(60, 70, 65), 12.0, 64.0), 1e-9)
        // Terrain drops away: hold height rather than following it down into the valley
        assertEquals(89.5, DroneFlightController.cruiseAltitude(listOf(40, 42), 12.0, 90.0), 1e-9)
        assertEquals(90.0, DroneFlightController.cruiseAltitude(emptyList(), 12.0, 90.0), 1e-9)
    }
}

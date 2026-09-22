package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionTest {

    private val eye = Vec3(0.0, 64.0, 0.0)

    /** Yaw 0 faces +Z in Minecraft, so this is "directly in front". */
    @Test
    fun `straight ahead is seen`() {
        assertTrue(Vision.inCone(eye, 0f, Vec3(0.0, 64.0, 10.0)))
    }

    @Test
    fun `directly behind is not seen`() {
        assertFalse(Vision.inCone(eye, 0f, Vec3(0.0, 64.0, -10.0)))
    }

    @Test
    fun `just inside the arc is seen and just outside is not`() {
        // 150 degrees total, so the edge sits at 75 either side of forward.
        fun at(angleDeg: Double): Vec3 {
            val r = Math.toRadians(angleDeg)
            return Vec3(-Math.sin(r) * 10.0, 64.0, Math.cos(r) * 10.0)
        }
        assertTrue(Vision.inCone(eye, 0f, at(70.0)))
        assertFalse(Vision.inCone(eye, 0f, at(80.0)))
        assertTrue(Vision.inCone(eye, 0f, at(-70.0)))
        assertFalse(Vision.inCone(eye, 0f, at(-80.0)))
    }

    @Test
    fun `turning the head brings a target into the arc`() {
        val behindRight = Vec3(-10.0, 64.0, 0.0) // 90 degrees off, outside the arc facing 0
        assertFalse(Vision.inCone(eye, 0f, behindRight))
        assertTrue(Vision.inCone(eye, 90f, behindRight))
    }

    @Test
    fun `height alone never hides anything`() {
        // Straight ahead but far above — pitch is deliberately not part of the test.
        assertTrue(Vision.inCone(eye, 0f, Vec3(0.0, 120.0, 10.0)))
    }

    @Test
    fun `a target in the same spot is always noticed`() {
        assertTrue(Vision.inCone(eye, 0f, eye))
    }
}

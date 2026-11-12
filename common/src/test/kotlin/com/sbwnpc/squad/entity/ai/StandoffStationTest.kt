package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandoffStationTest {
    private val target = Vec3(100.0, 0.0, 100.0)
    private val heli = Vec3(0.0, 40.0, 100.0)

    @Test fun `with nobody else about the gunship stands off on the side it comes from`() {
        val point = StandoffStation.point(heli, target, emptyList(), 20.0)
        assertEquals(80.0, point.x, 1e-6)
        assertEquals(100.0, point.z, 1e-6)
    }

    @Test fun `it goes round the target to the side farthest from another enemy`() {
        val other = Vec3(76.0, 0.0, 100.0)
        val point = StandoffStation.point(heli, target, listOf(other), 20.0)
        assertTrue(Vec3(point.x - other.x, 0.0, point.z - other.z).length() >= 40.0, "Hovers $point near the other enemy")
        assertEquals(20.0, Vec3(point.x - target.x, 0.0, point.z - target.z).length(), 1e-6)
    }

    @Test fun `the target itself among the contacts does not push the point`() {
        val point = StandoffStation.point(heli, target, listOf(target), 20.0)
        assertEquals(80.0, point.x, 1e-6)
    }
}

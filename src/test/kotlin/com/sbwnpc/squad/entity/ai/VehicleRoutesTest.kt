package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleRoutesTest {
    private val from = Vec3(0.5, 64.0, 0.5)
    private val goal = Vec3(300.5, 100.0, 0.5)

    @Test
    fun `a useful partial hull route overrides a premature map stop`() {
        assertTrue(VehicleRoutes.leadsOn(from, Vec3(40.5, 74.0, 10.5), goal, false))
    }

    @Test
    fun `failed one node and backwards partial routes do not prolong an impassable trip`() {
        assertFalse(VehicleRoutes.leadsOn(from, null, goal, false))
        assertFalse(VehicleRoutes.leadsOn(from, from.add(1.0, 0.0, 0.0), goal, false))
        assertFalse(VehicleRoutes.leadsOn(from, from.add(-40.0, 0.0, 0.0), goal, false))
    }

    @Test
    fun `a complete hull route may take a detour beyond the stop`() {
        assertTrue(VehicleRoutes.leadsOn(from, from.add(0.0, 0.0, 40.0), goal, true))
        assertFalse(VehicleRoutes.leadsOn(from, from.add(0.0, 0.0, 40.0), goal, false))
    }

    @Test
    fun `hull start and target use the same corner coordinates as path waypoints`() {
        // Recorded moving/blocked BMP positions; positive and negative axes both matter.
        for (position in listOf(Vec3(437.572979, 126.0, -1999.817381),
                Vec3(560.276018, 132.470832, -2011.050342), Vec3(-560.27, 64.5, 2011.05))) {
            val node = VehicleRoutes.cornerOf(position, 3.6f)
            val centre = VehicleRoutes.centreOf(node, 3.6f)
            assertTrue(kotlin.math.abs(centre.x - position.x) < 1.0)
            assertTrue(kotlin.math.abs(centre.z - position.z) < 1.0)
            assertTrue(kotlin.math.abs(centre.y - position.y) <= 0.5)
        }
    }
}

package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmokeScreenTest {
    @Test fun `a quarter of the squad throws, rounded down`() {
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3), listOf(3, 1, 4, 7, 8, 10, 12).map(SmokeScreen::grenadesFor))
    }

    @Test fun `one grenade is aimed straight at the enemy on the level`() {
        val point = SmokeScreen.screenPoints(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 80.0, 0.0), 1).single()
        assertEquals(10.0, point.x, 1e-9)
        assertEquals(64.0, point.y, 1e-9)
        assertEquals(0.0, point.z, 1e-9)
    }

    @Test fun `several grenades lie across the way to the enemy, centred on it`() {
        val points = SmokeScreen.screenPoints(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 64.0, 0.0), 3)
        assertTrue(points.all { it.x == 10.0 }, "All the same distance out: $points")
        assertEquals(listOf(-7.0, 0.0, 7.0), points.map { it.z }.sorted())
    }

    @Test fun `an enemy straight above or below gives no direction`() {
        assertTrue(SmokeScreen.screenPoints(Vec3(0.0, 64.0, 0.0), Vec3(0.2, 90.0, 0.3), 2).isEmpty())
        assertEquals(1, SmokeScreen.screenPoints(Vec3(0.0, 64.0, 0.0), Vec3(5.0, 64.0, 0.0), 1).size)
    }
}

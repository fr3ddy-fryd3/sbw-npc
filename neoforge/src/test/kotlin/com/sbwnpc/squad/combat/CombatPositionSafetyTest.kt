package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CombatPositionSafetyTest {
    private val enemy = Vec3.ZERO
    private val origin = Vec3(80.0, 64.0, 0.0)

    @Test fun `firing posts and assault bounds cannot voluntarily enter close range`() {
        for ((role, distance) in listOf(NpcClass.RIFLEMAN to 48.0, NpcClass.MACHINE_GUNNER to 56.0, NpcClass.SNIPER to 64.0)) {
            val clearance = CombatPositionSafety.clearance(role)
            assertEquals(distance, clearance)
            assertTrue(CombatPositionSafety.permits(origin, Vec3(distance, 64.0, 0.0), enemy, clearance))
            assertFalse(CombatPositionSafety.permits(origin, Vec3(distance - 2.0, 64.0, 0.0), enemy, clearance))
        }
    }

    @Test fun `safe endpoints cannot route across an enemy position`() {
        val destination = Vec3(-80.0, 64.0, 0.0)
        assertTrue(CombatPositionSafety.permits(origin, destination, enemy, 48.0))
        assertFalse(CombatPositionSafety.permitsSegment(origin, origin, destination, enemy, 48.0))
        val side = Vec3(60.0, 64.0, 40.0)
        assertTrue(CombatPositionSafety.permitsSegment(origin, origin, side, enemy, 48.0))
    }

    @Test fun `a close defender can hold move sideways or escape without being forced to retreat`() {
        val close = Vec3(16.0, 64.0, 0.0)
        assertTrue(CombatPositionSafety.permits(close, close, enemy, 48.0))
        assertTrue(CombatPositionSafety.permits(close, close.add(0.0, 0.0, 4.0), enemy, 48.0))
        assertTrue(CombatPositionSafety.permitsSegment(close, close, close.add(8.0, 0.0, 0.0), enemy, 48.0))
        assertFalse(CombatPositionSafety.permits(close, close.add(-4.0, 0.0, 0.0), enemy, 48.0))
        assertTrue(CombatPositionSafety.permitsSegment(close, close, close.add(-0.5, 0.0, 0.0), enemy, 48.0), "block-centre rounding must not trap an occupied post")
    }

    @Test fun `an assault step is shortened before overshooting the firing distance`() {
        val from = Vec3(60.0, 64.0, 0.0)
        val point = CombatPositionSafety.approach(from, Vec3(36.0, 64.0, 0.0), enemy, 50.0)
        assertTrue(point.x in 49.2..50.0)
        assertEquals(from.y, point.y)
        assertTrue(CombatPositionSafety.permits(from, point, enemy, 48.0))
        assertEquals(from.add(0.0, 0.0, 12.0), CombatPositionSafety.approach(from, from.add(0.0, 0.0, 12.0), enemy, 50.0))
    }

    @Test fun `stopping horizontal approach still permits climbing at the safe column`() {
        val from = Vec3(60.0, 64.0, 0.0)
        val point = CombatPositionSafety.approach(from, Vec3(36.0, 72.0, 0.0), enemy, 50.0)
        assertTrue(point.x in 49.2..50.0)
        assertEquals(72.0, point.y)
    }

    @Test fun `an assault cuts the clearance to a short standoff but never lifts it`() {
        assertEquals(CombatPositionSafety.ASSAULT_CLEARANCE, CombatPositionSafety.clearance(NpcClass.RIFLEMAN, assault = true))
        assertEquals(CombatPositionSafety.clearance(NpcClass.SHOTGUNNER), CombatPositionSafety.clearance(NpcClass.SHOTGUNNER, assault = true))
        assertTrue(CombatPositionSafety.clearance(NpcClass.RIFLEMAN, assault = true) > 0.0)
        // A man already inside the standoff may hold but not come nearer.
        val enemy = Vec3(0.0, 64.0, 0.0)
        val from = Vec3(10.0, 64.0, 0.0)
        assertTrue(CombatPositionSafety.permits(from, Vec3(12.0, 64.0, 0.0), enemy, CombatPositionSafety.ASSAULT_CLEARANCE))
        assertFalse(CombatPositionSafety.permits(from, Vec3(4.0, 64.0, 0.0), enemy, CombatPositionSafety.ASSAULT_CLEARANCE))
    }
}

package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CombatPositionTest {
    @Test
    fun `defend and patrol never advance on an enemy inside twice the class assault range`() {
        for (order in listOf(SquadOrder.DEFEND, SquadOrder.PATROL)) {
            for (cls in NpcClass.entries) {
                val range = cls.assaultDistance
                assertFalse(CombatPosition.mayAdvance(order, cls, Vec3.ZERO, Vec3(range * 1.5, 0.0, 0.0)))
                assertFalse(CombatPosition.mayAdvance(order, cls, Vec3.ZERO, Vec3(range * 2.0, 0.0, 0.0)))
                assertTrue(CombatPosition.mayAdvance(order, cls, Vec3.ZERO, Vec3(range * 2.0 + 1.0, 0.0, 0.0)))
            }
        }
    }

    @Test
    fun `an assault can still advance on a distant commanded enemy`() {
        assertTrue(CombatPosition.mayAdvance(SquadOrder.ATTACK, NpcClass.RIFLEMAN, Vec3.ZERO, Vec3(300.0, 0.0, 0.0)))
    }

    @Test
    fun `small firing shifts cannot accumulate into a chase far from the original post`() {
        val patrolPost = Vec3(1000.0, 64.0, 500.0)
        assertTrue(CombatPosition.withinArea(patrolPost, patrolPost.add(24.0, 1.0, 0.0), CombatPosition.MAX_ADVANCE_DISTANCE))
        assertFalse(CombatPosition.withinArea(patrolPost, patrolPost.add(26.0, 0.0, 0.0), CombatPosition.MAX_ADVANCE_DISTANCE))
    }

    @Test
    fun `a far enemy allows a 24 block approach from the original post`() {
        assertEquals(Vec3(24.0, 64.0, 0.0),
            CombatPosition.advancePoint(NpcClass.RIFLEMAN, Vec3(0.0, 64.0, 0.0), Vec3(200.0, 64.0, 0.0)))
        assertNull(CombatPosition.advancePoint(NpcClass.RIFLEMAN, Vec3.ZERO, Vec3(60.0, 0.0, 0.0)))
    }

    @Test
    fun `approach stops at doubled range instead of using up the whole allowance`() {
        val goal = CombatPosition.advancePoint(NpcClass.RIFLEMAN, Vec3.ZERO, Vec3(100.0, 0.0, 0.0))!!
        assertEquals(Vec3(4.0, 0.0, 0.0), goal)
        assertFalse(CombatPosition.mayAdvance(SquadOrder.PATROL, NpcClass.RIFLEMAN, goal, Vec3(100.0, 0.0, 0.0)))
        // A target approaching afterwards does not send the defender back to restore the range.
        assertFalse(CombatPosition.mayAdvance(SquadOrder.DEFEND, NpcClass.RIFLEMAN, goal, Vec3(80.0, 0.0, 0.0)))
    }

    @Test
    fun `a rear support post remains inside the defend area after the assault takes its point`() {
        for (cls in listOf(NpcClass.SNIPER, NpcClass.MEDIC, NpcClass.MACHINE_GUNNER)) {
            val post = SquadFormation.attackOffset(cls, 0, 7)
            assertTrue(CombatPosition.withinArea(Vec3.ZERO, post, CombatPosition.defendRadius(cls, 7)))
        }
    }
}

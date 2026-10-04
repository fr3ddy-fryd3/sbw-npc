package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.SquadOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SquadFormationTest {
    private val support = listOf(NpcClass.SNIPER, NpcClass.MEDIC, NpcClass.MACHINE_GUNNER)

    @Test
    fun `support marches to an empty attack objective in the ordinary formation`() {
        for (cls in support) {
            for (arrived in listOf(false, true)) {
                for (slot in 0 until 32) {
                    val expected = SquadFormation.formationOffset(SquadOrder.ATTACK, arrived, NpcClass.RIFLEMAN, slot, 32)
                    val actual = SquadFormation.formationOffset(SquadOrder.ATTACK, arrived, cls, slot, 32)
                    assertEquals(expected, actual, "$cls gets a distant combat post without an enemy")
                }
            }
            assertEquals(0.0, SquadFormation.formationOffset(SquadOrder.ATTACK, false, cls, 0, 7).lengthSqr())
            assertEquals(4.0, SquadFormation.formationOffset(SquadOrder.ATTACK, true, cls, 0, 7).length())
        }
    }

    @Test
    fun `contact enables rear combat posts independently of the commanded objective`() {
        for (cls in support) {
            val post = SquadFormation.formationOffset(
                SquadOrder.ATTACK, false, cls, 0, 7, SquadFormation.COMBAT_SPACING, combat = true
            )
            assertTrue(post.z <= -cls.attackStandoffDistance)
            assertTrue(post.length() <= cls.assaultDistance)
        }
    }

    @Test
    fun `support stays behind the riflemen even when casualties put it in the lead slot`() {
        val assaultRange = 24.0 * NpcClass.RIFLEMAN.shootDistanceMultiplier
        for (cls in support) {
            assertTrue(cls.attackStandoffDistance > assaultRange)
            assertTrue(cls.shootDistanceMultiplier > NpcClass.RIFLEMAN.shootDistanceMultiplier)
            for (slot in 0 until 32) {
                val post = SquadFormation.attackOffset(cls, slot, 32, SquadFormation.COMBAT_SPACING)
                assertTrue(post.z <= -cls.attackStandoffDistance, "$cls slot $slot is too close: $post")
                assertTrue(post.length() <= 24.0 * cls.shootDistanceMultiplier,
                    "$cls slot $slot cannot cover the objective from $post")
            }
        }
    }

    @Test
    fun `largest preset gives every support member a separate rear post`() {
        val composition = SquadPreset.THIRTY_TWO.composition
        for (spacing in listOf(3.0, SquadFormation.COMBAT_SPACING)) {
            val posts = composition.mapIndexedNotNull { index, cls ->
                if (cls in support) SquadFormation.attackOffset(cls, index, composition.size, spacing) else null
            }
            assertEquals(12, posts.size)
            assertEquals(posts.size, posts.toSet().size)
        }
    }

    @Test
    fun `rifleman lead still advances to the assault point`() {
        assertEquals(0.0, SquadFormation.attackOffset(NpcClass.RIFLEMAN, 0, 32).lengthSqr())
    }
}

package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.tactics.CombatStrength.Count
import com.sbwnpc.squad.ai.tactics.CombatStrength.Start
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CombatStrengthTest {
    @Test fun `under a third of the full complement alive is a remnant, whatever ammunition it has`() {
        assertEquals(Start.REMNANT, CombatStrength.start(Count(full = 16, living = 4, able = 4), canResupply = true))
        assertEquals(Start.REMNANT, CombatStrength.start(Count(full = 16, living = 5, able = 0), canResupply = true))
        assertEquals(Start.ATTACK, CombatStrength.start(Count(full = 16, living = 6, able = 6), canResupply = true))
    }

    @Test fun `half the living dry refills first while a Supply is known, and not again`() {
        val dry = Count(full = 16, living = 12, able = 6)
        assertEquals(Start.RESUPPLY, CombatStrength.start(dry, canResupply = true))
        assertEquals(Start.ATTACK, CombatStrength.start(dry, canResupply = false))
        assertTrue(CombatStrength.poorChances(dry))
        assertEquals(Start.ATTACK, CombatStrength.start(Count(full = 16, living = 12, able = 7), canResupply = true))
        assertFalse(CombatStrength.poorChances(Count(full = 16, living = 12, able = 7)))
    }

    @Test fun `standing is the share of the base lost`() {
        assertEquals(OperationStanding.STEADY, CombatStrength.standing(able = 8, base = 8))
        assertEquals(OperationStanding.STEADY, CombatStrength.standing(able = 6, base = 8))
        assertEquals(OperationStanding.REVIEW, CombatStrength.standing(able = 5, base = 8))
        assertEquals(OperationStanding.REVIEW, CombatStrength.standing(able = 4, base = 8))
        assertEquals(OperationStanding.WITHDRAW, CombatStrength.standing(able = 3, base = 8))
    }

    @Test fun `strength regained lifts the hold, and an attack with no base has nothing to lose`() {
        assertEquals(OperationStanding.REVIEW, CombatStrength.standing(able = 5, base = 8))
        assertEquals(OperationStanding.STEADY, CombatStrength.standing(able = 8, base = 8))
        assertEquals(OperationStanding.STEADY, CombatStrength.standing(able = 0, base = 0))
    }
}

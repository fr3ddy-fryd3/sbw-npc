package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.Cap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MandatoryRulesTest {
    @Test fun `the rules stand in the owner's order`() {
        // Owner, 2.11: evade a live grenade, a drone, self-bandage, medic help, out of ammunition, resupply.
        assertEquals(
            listOf("evade_grenade", "anti_drone", "self_treat", "medic_help", "out_of_ammo", "resupply", "blind_return_fire"),
            MandatoryRule.entries.map { it.id },
        )
    }

    @Test fun `a higher rule's claim outranks a lower one's wherever both take the same capability`() {
        val ruled = MandatoryRule.entries.filter { it.activity != null }
        for ((i, higher) in ruled.withIndex()) {
            for (lower in ruled.drop(i + 1)) {
                if (higher.takes.none { it in lower.takes } || higher.activity == lower.activity) continue
                assertTrue(higher.activity!!.ordinal > lower.activity!!.ordinal, "$higher must rank above $lower")
            }
        }
    }

    @Test fun `blind return fire takes only the trigger`() {
        assertEquals(setOf(Cap.FIRE), MandatoryRule.BLIND_RETURN_FIRE.takes)
        assertEquals(null, MandatoryRule.BLIND_RETURN_FIRE.activity)
    }
}

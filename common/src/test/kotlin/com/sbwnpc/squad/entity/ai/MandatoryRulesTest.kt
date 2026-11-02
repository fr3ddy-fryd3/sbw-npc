package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.Cap
import com.sbwnpc.squad.entity.NpcActivities
import com.sbwnpc.squad.entity.NpcActivity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test fun `blind return fire takes the trigger and its aim, never the feet`() {
        assertEquals(setOf(Cap.FIRE, Cap.LOOK), MandatoryRule.BLIND_RETURN_FIRE.takes)
        assertEquals(null, MandatoryRule.BLIND_RETURN_FIRE.activity)
    }

    private fun owner(of: MandatoryRule) =
        object : MandatoryRuleOwner {
            override val rule = of
        }

    private class Man {
        val activities = NpcActivities()
        val holds: (NpcActivity, (Any) -> Boolean) -> Boolean = { kind, owner -> activities.has(kind, 0, owner) }
        fun claim(kind: NpcActivity, owner: Any) = activities.claim(kind, owner, 0)
    }

    @Test fun `a live grenade blocks the drone reaction and self-treatment, and suspends the plan above it`() {
        val man = Man()
        man.claim(NpcActivity.ANTI_DRONE, Any())
        man.claim(NpcActivity.EVADE, Any())
        assertEquals(MandatoryRule.EVADE_GRENADE, MandatoryRules.blockedBy(MandatoryRule.ANTI_DRONE, man.holds))
        assertEquals(MandatoryRule.EVADE_GRENADE, MandatoryRules.blockedBy(MandatoryRule.SELF_TREAT, man.holds))
        assertEquals(MandatoryRule.EVADE_GRENADE, MandatoryRules.suspending(man.holds))
    }

    @Test fun `out of ammunition and resupply are told apart by whose claim it is`() {
        val running = Man().apply { claim(NpcActivity.RESUPPLY, owner(MandatoryRule.OUT_OF_AMMO)) }
        assertEquals(MandatoryRule.OUT_OF_AMMO, MandatoryRules.blockedBy(MandatoryRule.RESUPPLY, running.holds))
        assertEquals(MandatoryRule.OUT_OF_AMMO, MandatoryRules.suspending(running.holds))
        val walking = Man().apply { claim(NpcActivity.RESUPPLY, owner(MandatoryRule.RESUPPLY)) }
        assertNull(MandatoryRules.blockedBy(MandatoryRule.OUT_OF_AMMO, walking.holds))
        assertEquals(MandatoryRule.RESUPPLY, MandatoryRules.suspending(walking.holds))
    }

    @Test fun `a man walking to a Supply may still fire back blindly, a medic at work may not`() {
        val walking = Man().apply { claim(NpcActivity.RESUPPLY, owner(MandatoryRule.RESUPPLY)) }
        assertNull(MandatoryRules.blockedBy(MandatoryRule.BLIND_RETURN_FIRE, walking.holds))
        val healing = Man().apply { claim(NpcActivity.HEAL, Any()) }
        assertEquals(MandatoryRule.MEDIC_HELP, MandatoryRules.blockedBy(MandatoryRule.BLIND_RETURN_FIRE, healing.holds))
        assertNull(MandatoryRules.blockedBy(MandatoryRule.SELF_TREAT, healing.holds))
    }
}

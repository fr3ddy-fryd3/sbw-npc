package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Armour is answered by one NPC method family, without changing bullet suppression. */
class ArmourResponseTest {
    private val actor = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val intent = PositionIntent(actor, CombatDuty.FIRE, Point3(0.0, 1.0, 0.0), Point3(10.0, 1.0, 0.0), Point3(60.0, 1.0, 0.0))
    private val contact = ArmourContact(UUID(0, 2), FireSector.of(60.0, 0.0))
    private fun facts() =
        FactSnapshot(actor, 1).observe(TacticalFacts.MOVABLE, true).observe(TacticalFacts.DIGS, 0)
            .observe(TacticalFacts.UNDER_FIRE, false).observe(ArmourFacts.CONTACT, contact).observe(ArmourFacts.LOADED, false)
            .observe(ArmourFacts.CYCLE, 0).observe(ArmourFacts.STEP, ArmourStep.COVER)

    private fun plan(facts: FactSnapshot, call: PositionIntent = intent) = (HtnPlanningSession(TacticalDomain.npc, PlanOwner(actor, UUID(1, 1)), TacticalDomain.NPC.call(call), facts).advance(50) as PlanningResult.Ready).plan

    @Test fun `an armour contact takes ordinary duty and assault into cover`() {
        for (call in listOf(intent, intent.copy(assault = true))) {
            val action = plan(facts(), call).tasks.first().spec.action
            assertEquals(ArmourResponse.ACTION, action.key)
            assertEquals(ArmourStep.COVER, (action.parameters as ArmourUnit).step)
        }
    }

    @Test fun `cover failure retreats even with an infantry target and never digs`() {
        val input = facts().observe(ArmourFacts.COVER_FAILED, true).observe(TacticalFacts.ENGAGED, true).observe(TacticalFacts.DIG_POSSIBLE, true)
        assertEquals(ArmourStep.ESCAPE, (plan(input).tasks.first().spec.action.parameters as ArmourUnit).step)
        assertEquals(ArmourStep.HOLD, ArmourResponse.choose(input.observe(ArmourFacts.ESCAPE_FAILED, true)))
        assertEquals(ArmourStep.SORTIE, ArmourResponse.choose(input.observe(ArmourFacts.LOADED, true)))
    }

    @Test fun `mixed bullet fire and armour select one family`() {
        for (fire in listOf(false, true)) {
            for (loaded in listOf(false, true)) {
                val input = facts().observe(TacticalFacts.UNDER_FIRE, fire).observe(ArmourFacts.LOADED, loaded)
                val allowed = ArrayList<String>()
                HtnPlanningSession(TacticalDomain.npc, PlanOwner(actor, UUID(1, 1)), TacticalDomain.NPC.call(intent), input, event = { if (it.node == "root" && it.verdict == Verdict.Allowed && it.method != null) allowed += it.method!! }).advance(50)
                assertEquals(listOf("answer_armour"), allowed.distinct())
            }
        }
    }

    @Test fun `foxholes and flying drones keep their existing ownership`() {
        assertFalse(ArmourResponse.active(facts().observe(TacticalFacts.DIGS, 1)))
        assertFalse(ArmourResponse.active(facts().observe(DroneFacts.AIRBORNE, true)))
    }

    @Test fun `sortie probes permit sideways and backwards but no forward projection`() {
        val origin = Point3(10.0, 1.0, 10.0)
        val source = Point3(60.0, 2.0, 10.0)
        assertTrue(ArmourResponse.lateralOrBack(origin, source, Point3(10.0, 1.0, 13.0)))
        assertTrue(ArmourResponse.lateralOrBack(origin, source, Point3(7.0, 1.0, 13.0)))
        assertFalse(ArmourResponse.lateralOrBack(origin, source, Point3(10.5, 1.0, 13.0)))
    }

    @Test fun `finishing an armour unit and losing the contact invalidate its plan`() {
        val input = facts()
        val planned = plan(input)
        assertFalse(planned.inputUnchanged(input.observe(ArmourFacts.CYCLE, 1)))
        assertFalse(planned.inputUnchanged(input.forget(ArmourFacts.CONTACT)))
    }

    @Test fun `a committed sortie survives leaving home but losing rockets chooses return`() {
        val moving = facts().observe(ArmourFacts.RUNNING, ArmourStep.SORTIE).observe(ArmourFacts.SHELTERED, false)
            .observe(ArmourFacts.SORTIE_LAUNCHER, true).observe(ArmourFacts.LOADED, true)
        assertEquals(ArmourStep.SORTIE, ArmourResponse.choose(moving))
        assertEquals(ArmourStep.RETURN, ArmourResponse.choose(moving.observe(ArmourFacts.LOADED, false)))
    }

    @Test fun `an unsuccessful sortie returns and an exposed rifleman cannot stay firing`() {
        assertEquals(ArmourStep.RETURN, ArmourResponse.choose(facts().observe(ArmourFacts.STEP, ArmourStep.SORTIE).observe(ArmourFacts.SORTIE_READY, false)))
        assertEquals(ArmourStep.RETURN, ArmourResponse.choose(facts().observe(ArmourFacts.RUNNING, ArmourStep.FIRE).observe(ArmourFacts.HIDDEN, false)))
    }

    @Test fun `an operator can launch from another sheltered point but ordinary suppression keeps him down`() {
        val operator = facts().observe(ArmourFacts.HIDDEN, true).observe(ArmourFacts.SHELTERED, false)
            .observe(DroneFacts.AIRBORNE, false).observe(DroneFacts.HOLD, DroneHold.NONE)
            .observe(DroneFacts.OPTIONS, StrikeOptions(mapOf(StrikeSource.FOCUS to StrikePoint(contact.id, intent.anchor))))
        assertTrue(ArmourResponse.launches(operator))
        assertFalse(ArmourResponse.launches(operator.observe(TacticalFacts.UNDER_FIRE, true)))
    }

    @Test fun `FOLLOW movers keep their agreed bound despite armour`() {
        assertFalse(ArmourResponse.answers(intent.copy(ordered = true), facts()))
        assertEquals(TacticalDomain.POSITION, plan(facts(), intent.copy(ordered = true)).tasks.first().spec.action.key)
    }
}

package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Armour knowledge and execution obligations remain independent of ordinary incoming fire. */
class ArmourResponseTest {
    private val actor = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val intent = PositionIntent(actor, CombatDuty.FIRE, Point3(0.0, 1.0, 0.0), Point3(10.0, 1.0, 0.0), Point3(60.0, 1.0, 0.0))
    private val contact = ArmourContact(UUID(0, 2), FireSector.of(60.0, 0.0))
    private fun facts() =
        FactSnapshot(actor, 1).observe(TacticalFacts.MOVABLE, true).observe(TacticalFacts.DIGS, 0)
            .observe(TacticalFacts.UNDER_FIRE, false).observe(ArmourFacts.CONTACT, contact).observe(ArmourFacts.LOADED, false)
            .observe(ArmourFacts.CYCLE, 0).observe(ArmourFacts.HIDDEN, false).observe(ArmourFacts.EXPOSED, true)

    private fun actions(input: FactSnapshot): List<ArmourUnit> {
        val result = HtnPlanningSession(HtnDomain(AgentLevel.NPC, ArmourResponse.tasks()), PlanOwner(actor, UUID(1, 1)), ArmourResponse.TASK.call(intent), input).advance(50) as PlanningResult.Ready
        return result.plan.tasks.map { it.spec.action.parameters as ArmourUnit }
    }

    @Test fun `remembered hidden armour does not take ordinary infantry combat`() {
        assertFalse(ArmourResponse.answers(intent, facts().observe(ArmourFacts.HIDDEN, true).observe(ArmourFacts.EXPOSED, false)))
    }

    @Test fun `ordinary incoming fire keeps its existing priority over armour`() {
        assertFalse(ArmourResponse.answers(intent, facts().observe(TacticalFacts.UNDER_FIRE, true)))
    }

    @Test fun `exposed infantry seeks cover then escapes failed geometry`() {
        assertEquals(listOf(ArmourStep.COVER), actions(facts()).map { it.step })
        assertEquals(listOf(ArmourStep.ESCAPE), actions(facts().observe(ArmourFacts.COVER_FAILED, true)).map { it.step })
        assertEquals(listOf(ArmourStep.HOLD), actions(facts().observe(ArmourFacts.COVER_FAILED, true).observe(ArmourFacts.ESCAPE_FAILED, true)).map { it.step })
    }

    @Test fun `launcher sortie is a dependency sequence rather than a last-step controller`() {
        val units = actions(facts().observe(ArmourFacts.HIDDEN, true).observe(ArmourFacts.EXPOSED, false).observe(ArmourFacts.SHELTERED, true).observe(ArmourFacts.LOADED, true).observe(ArmourFacts.SORTIE_ALLOWED, true))
        assertEquals(listOf(ArmourStep.SORTIE, ArmourStep.FIRE, ArmourStep.RETURN), units.map { it.step })
        assertEquals(listOf(0, 1, 2), units.map { it.cycle })
    }

    @Test fun `return obligation survives lost exposure and ammunition`() {
        val input = facts().observe(ArmourFacts.HIDDEN, true).observe(ArmourFacts.EXPOSED, false).observe(ArmourFacts.RETURN_DUE, true)
        assertTrue(ArmourResponse.answers(intent, input))
        assertEquals(listOf(ArmourStep.RETURN), actions(input).map { it.step })
    }

    @Test fun `failed launcher searches do not monopolize sheltered infantry combat`() {
        assertFalse(ArmourResponse.answers(intent, facts().observe(ArmourFacts.HIDDEN, true).observe(ArmourFacts.EXPOSED, false).observe(ArmourFacts.LOADED, true).observe(ArmourFacts.SORTIE_ALLOWED, false)))
    }

    @Test fun `foxholes flying drones and ordered FOLLOW movers preserve ownership`() {
        assertFalse(ArmourResponse.active(facts().observe(TacticalFacts.DIGS, 1)))
        assertFalse(ArmourResponse.active(facts().observe(DroneFacts.AIRBORNE, true)))
        assertFalse(ArmourResponse.answers(intent.copy(ordered = true), facts()))
    }

    @Test fun `sortie probes permit sideways and backwards but no forward projection`() {
        val origin = Point3(10.0, 1.0, 10.0)
        val source = Point3(60.0, 2.0, 10.0)
        assertTrue(ArmourResponse.lateralOrBack(origin, source, Point3(10.0, 1.0, 13.0)))
        assertTrue(ArmourResponse.lateralOrBack(origin, source, Point3(7.0, 1.0, 13.0)))
        assertFalse(ArmourResponse.lateralOrBack(origin, source, Point3(10.5, 1.0, 13.0)))
    }
}

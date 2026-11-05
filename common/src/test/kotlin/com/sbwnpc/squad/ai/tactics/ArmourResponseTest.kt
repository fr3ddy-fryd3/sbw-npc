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

    @Test fun `outing retains completed sortie across replanning and ammo loss selects return`() {
        val starts = ArrayList<ArmourStep>()
        var completed = false
        val runtime = PlanRuntime({ _, spec ->
            val unit = spec.parameters as ArmourUnit
            object : ActionExecution {
                override fun start(now: Long): ActionUpdate {
                    starts += unit.step
                    return ActionUpdate.Running
                }
                override fun tick(now: Long): ActionUpdate = if (completed) ActionUpdate.Completed(listOf(ActionEvidence("physical_result", now))) else ActionUpdate.Running
                override fun suspend(now: Long, reason: String) = Unit
                override fun resume(now: Long) = Unit
                override fun cancel(now: Long, reason: String) = Unit
            }
        }, ResourceLeases())
        val domain = HtnDomain(AgentLevel.NPC, ArmourResponse.tasks())
        val agent = PlanningAgent(actor, domain, runtime)
        val initial = facts().at(10).observe(ArmourFacts.HIDDEN, true).observe(ArmourFacts.EXPOSED, false).observe(ArmourFacts.LOADED, true).observe(ArmourFacts.SORTIE_ALLOWED, true)
        agent.command(UUID(3, 1), ArmourResponse.TASK.call(intent), initial)
        agent.tick(initial, planningWork = 50)
        val outing = initial.at(11).observe(ArmourFacts.COMMITMENT, ArmourCommitment(contact, 0)).observe(ArmourFacts.HIDDEN, false)
        agent.tick(outing, planningWork = 50)
        assertEquals(listOf(ArmourStep.SORTIE), starts)
        completed = true
        val arrived = outing.at(12).observe(ArmourFacts.CYCLE, 1)
        agent.tick(arrived, planningWork = 50)
        completed = false
        agent.tick(arrived.at(13), planningWork = 50)
        assertEquals(listOf(ArmourStep.SORTIE, ArmourStep.FIRE), starts)
        agent.tick(arrived.at(14).observe(ArmourFacts.LOADED, false), planningWork = 50)
        assertEquals(ArmourStep.RETURN, starts.last())
        assertEquals(1, starts.count { it == ArmourStep.SORTIE })
    }

    @Test fun `stale open ray alone cannot take movement`() {
        assertFalse(ArmourResponse.answers(intent, facts().observe(ArmourFacts.EXPOSED, false)))
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

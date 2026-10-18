package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalDomainTest {
    private val actor = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val intent = PositionIntent(actor, CombatDuty.MOVE, Point3(0.0, 1.0, 0.0), Point3(50.0, 1.0, 0.0), Point3(100.0, 1.0, 0.0), requireCover = true)
    private val owner = PlanOwner(actor, UUID(1, 1))
    private fun facts(now: Long) = FactSnapshot(actor, now).observe(TacticalFacts.MOVABLE, true).observe(TacticalFacts.COVER, true)
    private fun plan(input: FactSnapshot): PlanningResult = HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(intent), input).advance(20)

    @Test fun `local exhaustion selects neighboring query while retaining original parent mission`() {
        val input = facts(0)
        val first = (plan(input) as PlanningResult.Ready).plan
        val failure = LocalPositionFailure(intent.anchor, 1, 1, mapOf("blocked_shot" to 24))
        val changed = facts(1).observe(TacticalFacts.POST_FAILURE, failure, expiresAt = 201)
        assertFalse(first.inputUnchanged(changed))
        val recovered = (plan(changed) as PlanningResult.Ready).plan
        val selected = recovered.tasks.single().spec.action.parameters as PositionIntent
        assertNotEquals(intent.anchor, selected.anchor)
        assertEquals(intent.actor, selected.actor)
        assertTrue(selected.requireCover)
        assertEquals(Point3(50.0, 1.0, 0.0), intent.anchor)
        assertEquals(selected.actor, recovered.owner.agent)
    }

    @Test fun `exhausted local alternatives wait for owner or expired evidence without declaring global impossibility`() {
        val failure = LocalPositionFailure(intent.anchor, 1, 5, mapOf("unreachable_route" to 5))
        val input = facts(1).observe(TacticalFacts.POST_FAILURE, failure, expiresAt = 201)
        val result = plan(input) as PlanningResult.Failed
        assertTrue("local_alternatives_exhausted" in result.reasons)
        assertFalse(result.limitReached)
        assertTrue(plan(input.at(201)) is PlanningResult.Ready)
    }

    @Test fun `a blind neighbourhood moves the search closer to the enemy`() {
        val blind = LocalPositionFailure(intent.anchor, 1, 5, mapOf("blocked_shot" to 93))
        val selected = (plan(facts(1).observe(TacticalFacts.POST_FAILURE, blind, expiresAt = 201)) as PlanningResult.Ready).plan.tasks.single().spec.action.parameters as PositionIntent
        assertEquals(Point3(62.0, 1.0, 0.0), selected.anchor)
        val next = blind.copy(attempts = 6)
        val second = (plan(facts(1).observe(TacticalFacts.POST_FAILURE, next, expiresAt = 201)) as PlanningResult.Ready).plan.tasks.single().spec.action.parameters as PositionIntent
        assertEquals(Point3(74.0, 1.0, 0.0), second.anchor)
    }

    @Test fun `closer anchors keep the role standoff and stop when used up`() {
        val anchors = TacticalDomain.closerAnchors(intent.copy(standoff = 40.0))
        assertTrue(anchors.isNotEmpty())
        anchors.forEach { assertTrue(kotlin.math.hypot(it.x - 100.0, it.z) >= 40.0) }
        assertTrue(TacticalDomain.closerAnchors(intent.copy(duty = CombatDuty.REAR_GUARD)).isEmpty())
        val used = LocalPositionFailure(intent.anchor, 1, 5 + TacticalDomain.closerAnchors(intent).size, mapOf("blocked_shot" to 93))
        assertTrue("closer_alternatives_exhausted" in (plan(facts(1).observe(TacticalFacts.POST_FAILURE, used, expiresAt = 201)) as PlanningResult.Failed).reasons)
    }

    @Test fun `foreign actor intent cannot reserve another NPC motion`() {
        val foreign = intent.copy(actor = AgentId(AgentLevel.NPC, UUID(5, 5)))
        val result = HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(foreign), facts(0)).advance(20) as PlanningResult.Failed
        assertTrue("foreign_actor_intent" in result.reasons)
    }

    @Test fun `utility rejects advance without capability and chooses valid height strategy`() {
        val squadFacts = FactSnapshot(AgentId(AgentLevel.SQUAD, UUID(3, 3)), 0)
        val blocked = StrategyEvaluation.evaluate(squadFacts, CombatSituation(true, true, false, false, 0.0, 0.0, false))
        assertTrue(blocked.excluded.any { it.id == CombatStrategy.ADVANCE.name })
        assertEquals(CombatStrategy.HOLD, blocked.ranked.first().value)
        val height = StrategyEvaluation.evaluate(squadFacts, CombatSituation(true, true, true, false, 1.0, 0.0, true))
        assertEquals(CombatStrategy.GAIN_HEIGHT, height.ranked.first().value)
    }

    @Test fun `withdrawal changes execution goal away from known threat without requiring cover fire`() {
        val squadId = AgentId(AgentLevel.SQUAD, UUID(0, 9))
        val mission = SquadMission(squadId, listOf(TeamMission(AgentId(AgentLevel.TEAM, UUID(0, 8)), listOf(intent))))
        val input = FactSnapshot(squadId, 0).observe(TacticalFacts.STRATEGY, CombatStrategy.WITHDRAW)
        val result = HtnPlanningSession(TacticalDomain.squad, PlanOwner(squadId, UUID(1, 1)), TacticalDomain.SQUAD.call(mission), input).advance(20) as PlanningResult.Ready
        val delegation = result.plan.tasks.single().spec.action.parameters as com.sbwnpc.squad.ai.coordination.DelegationTask
        val retreat = (delegation.task.parameters as TeamMission).members.single()
        assertTrue(retreat.anchor.x < intent.origin.x)
        assertFalse(retreat.requireCover)
        assertFalse(retreat.requireShelter)
        assertEquals(CombatDuty.MOVE, retreat.duty)
        assertEquals(Point3(50.0, 1.0, 0.0), intent.anchor)
    }

    @Test fun `a sentry withdrawing from his height may come down`() {
        val squadId = AgentId(AgentLevel.SQUAD, UUID(0, 9))
        val sentry = intent.copy(duty = CombatDuty.OBSERVE, minimumHeight = intent.origin.y - 2, elevationWeight = 3.0)
        val mission = SquadMission(squadId, listOf(TeamMission(AgentId(AgentLevel.TEAM, UUID(0, 8)), listOf(sentry))))
        val input = FactSnapshot(squadId, 0).observe(TacticalFacts.STRATEGY, CombatStrategy.WITHDRAW)
        val result = HtnPlanningSession(TacticalDomain.squad, PlanOwner(squadId, UUID(1, 1)), TacticalDomain.SQUAD.call(mission), input).advance(20) as PlanningResult.Ready
        val delegation = result.plan.tasks.single().spec.action.parameters as com.sbwnpc.squad.ai.coordination.DelegationTask
        val retreat = (delegation.task.parameters as TeamMission).members.single()
        assertEquals(CombatDuty.MOVE, retreat.duty)
        assertNull(retreat.minimumHeight)
        assertEquals(0.0, retreat.elevationWeight)
    }
}

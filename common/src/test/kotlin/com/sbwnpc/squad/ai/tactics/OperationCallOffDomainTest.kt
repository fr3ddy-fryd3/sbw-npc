package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** The operation's plan calls a beaten attack off before coordinating anything (#252). */
class OperationCallOffDomainTest {
    private val operation = UUID(0, 9)
    private val root = AgentId(AgentLevel.OPERATION, operation)
    private val owner = PlanOwner(root, UUID(1, 1))
    private val squad = UUID(0, 2)
    private val emptySquad = SquadMission(AgentId(AgentLevel.SQUAD, squad), emptyList())

    private fun plan(mission: OperationMission, phase: AttackPhase? = null): HtnPlan {
        val facts = FactSnapshot(root, 1).let { if (phase != null) it.observe(TacticalFacts.ATTACK_PHASE, phase) else it }
        return (HtnPlanningSession(TacticalDomain.operation, owner, TacticalDomain.OPERATION.call(mission), facts).advance(20) as PlanningResult.Ready).plan
    }

    @Test fun `a called-off attack plans only the call-off, whatever its phase`() {
        val callOff = CallOff(operation, listOf(squad), 2, 8, partial = false, fallBacks = mapOf(squad to FallBack(Point3(1.0, 64.0, 1.0), toSupply = true)))
        AttackPhase.entries.map { it as AttackPhase? }.plus(null).forEach { phase ->
            val task = plan(OperationMission(listOf(emptySquad), callOff = callOff), phase).tasks.single()
            assertEquals(TacticalDomain.CALL_OFF, task.spec.action.key)
            assertSame(callOff, task.spec.action.parameters)
        }
    }

    @Test fun `an attack weighing its losses plans as the review, not as its phase`() {
        val reviewing = plan(OperationMission(listOf(emptySquad), review = true), AttackPhase.entries.first())
        assertTrue(reviewing.tasks.none { it.spec.action.key == TacticalDomain.CALL_OFF })
        assertTrue(reviewing.tasks.all { "/review" in it.node }, reviewing.tasks.map { it.node }.toString())
        val steady = plan(OperationMission(listOf(emptySquad)), AttackPhase.entries.first())
        assertTrue(steady.tasks.none { "/review" in it.node })
    }
}

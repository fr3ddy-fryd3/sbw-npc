package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** The tank's plan (#185): the former escort rule of `FireSupport.holdEscortPace`, as methods. */
class TankSupportDomainTest {
    private val driver = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val tank = Tank(AgentId(AgentLevel.TEAM, UUID(0, 9)), driver)
    private val infantry = listOf(UUID(3, 1), UUID(3, 2))

    private fun facts(escorted: Boolean, engaged: Boolean?): FactSnapshot {
        var f = FactSnapshot(tank.agent, 0)
        if (engaged != null) f = f.observe(TankFacts.ENGAGED, engaged)
        if (escorted) f = f.observe(TankFacts.ESCORTED, EscortedSquads(infantry))
        return f
    }

    private fun applying(f: FactSnapshot) = TankSupport.ORDER.filter { TankSupport.applies(it).evaluate(f) == Verdict.Allowed }

    private fun duty(f: FactSnapshot): DriveDuty {
        val plan = (HtnPlanningSession(TacticalDomain.team, PlanOwner(tank.agent, UUID(1, 1)), TankSupport.TEAM_TASK.call(tank), f).advance(40) as PlanningResult.Ready).plan
        return plan.tasks.map { (it.spec.action.parameters as DelegationTask).task.parameters }.single() as DriveDuty
    }

    @Test fun `a tank with infantry that has not met the enemy keeps its pace`() {
        val f = facts(escorted = true, engaged = false)
        assertEquals(listOf(TankSupport.Plan.ESCORT), applying(f))
        assertEquals(DriveDuty(driver, DriveMode.PACE, infantry), duty(f))
    }

    @Test fun `once the command has met the enemy the tank drives at its own speed`() {
        val f = facts(escorted = true, engaged = true)
        assertEquals(listOf(TankSupport.Plan.DRIVE), applying(f))
        assertEquals(DriveDuty(driver, DriveMode.FREE), duty(f))
    }

    @Test fun `a tank sent without infantry drives at its own speed`() {
        assertEquals(listOf(TankSupport.Plan.DRIVE), applying(facts(escorted = false, engaged = false)))
        assertEquals(DriveDuty(driver, DriveMode.FREE), duty(facts(escorted = false, engaged = false)))
    }

    @Test fun `an unknown contact keeps the escort undecided rather than driving off`() {
        assertTrue(TankSupport.applies(TankSupport.Plan.DRIVE).evaluate(facts(escorted = true, engaged = null)) is Verdict.Unknown)
    }

    @Test fun `a support-only squad of one tank delegates its driving`() {
        val mission = SquadMission(AgentId(AgentLevel.SQUAD, UUID(2, 2)), emptyList(), tanks = listOf(tank))
        val plan = (HtnPlanningSession(TacticalDomain.squad, PlanOwner(mission.agent, UUID(1, 1)), TacticalDomain.SQUAD.call(mission), FactSnapshot(mission.agent, 0)).advance(40) as PlanningResult.Ready).plan
        val delegated = plan.tasks.map { (it.spec.action.parameters as DelegationTask) }
        assertEquals(listOf(tank.agent), delegated.map { it.child })
        assertEquals(TankSupport.TEAM_TASK, delegated.single().task.key)
    }
}

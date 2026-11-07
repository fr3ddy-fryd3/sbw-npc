package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** The helicopter's plan (#260, #261): the pilot's former mission choice, branch for branch, and one target for the crew. */
class AirSupportDomainTest {
    private val pilot = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val gunner = AgentId(AgentLevel.NPC, UUID(0, 2))
    private val aircraft = Aircraft(AgentId(AgentLevel.TEAM, UUID(0, 9)), pilot, listOf(gunner))
    private val target = UUID(5, 5)

    private data class Situation(
        val frame: Airframe = Airframe.GUNSHIP,
        val fit: Boolean = true,
        val order: AirOrder = AirOrder.ATTACK,
        val objective: Boolean = true,
        val target: Boolean = false,
        val inContact: Boolean = false,
        val metEnemy: Boolean = false,
    )

    private fun facts(s: Situation): FactSnapshot {
        var f = FactSnapshot(aircraft.agent, 0).observe(AirFacts.AIRFRAME, s.frame).observe(AirFacts.FIT, s.fit).observe(AirFacts.ORDER, s.order)
            .observe(AirFacts.HAS_OBJECTIVE, s.objective).observe(AirFacts.IN_CONTACT, s.inContact).observe(AirFacts.MET_ENEMY, s.metEnemy)
        if (s.target) f = f.observe(AirFacts.TARGET, target)
        return f
    }

    private fun situations(): List<Situation> {
        val b = listOf(false, true)
        val axes: List<(Situation) -> List<Situation>> = listOf(
            { s -> Airframe.entries.map { s.copy(frame = it) } },
            { s -> b.map { s.copy(fit = it) } },
            { s -> AirOrder.entries.map { s.copy(order = it) } },
            { s -> b.map { s.copy(objective = it) } },
            { s -> b.map { s.copy(target = it) } },
            { s -> b.map { s.copy(inContact = it) } },
            { s -> b.map { s.copy(metEnemy = it) } },
        )
        return axes.fold(listOf(Situation())) { all, axis -> all.flatMap(axis) }
    }

    /** HelicopterPilotBehaviour.decideMission as it stood before #260, the reference the methods reproduce. */
    private fun former(s: Situation): AirSupport.Plan {
        val gunship = s.frame == Airframe.GUNSHIP
        return when {
            !s.fit -> AirSupport.Plan.BREAK_OFF
            s.order == AirOrder.MOVE || s.order == AirOrder.RETREAT -> AirSupport.Plan.RETURN_HOME
            !gunship && s.order == AirOrder.ATTACK && s.objective -> AirSupport.Plan.BENCH_ORBIT
            gunship -> formerGunship(s)
            s.order == AirOrder.DEFEND || s.order == AirOrder.PATROL -> AirSupport.Plan.LOITER
            else -> AirSupport.Plan.LAND
        }
    }

    private fun formerGunship(s: Situation): AirSupport.Plan =
        when {
            s.target -> AirSupport.Plan.STANDOFF
            s.inContact -> AirSupport.Plan.HOLD_CONTACT
            s.order == AirOrder.DEFEND || s.order == AirOrder.PATROL -> AirSupport.Plan.LOITER
            s.order == AirOrder.ATTACK && !s.metEnemy -> AirSupport.Plan.LOITER
            s.order == AirOrder.ATTACK -> AirSupport.Plan.WITHDRAW
            else -> AirSupport.Plan.LAND
        }

    private fun plan(s: Situation) = (HtnPlanningSession(TacticalDomain.team, PlanOwner(aircraft.agent, UUID(1, 1)), AirSupport.TEAM_TASK.call(aircraft), facts(s)).advance(40) as PlanningResult.Ready).plan

    private fun duties(s: Situation) = plan(s).tasks.map { (it.spec.action.parameters as DelegationTask).task.parameters }

    @Test fun `exactly one method applies for every situation, the one the pilot used to choose`() {
        val all = situations()
        all.forEach { s ->
            val input = facts(s)
            val applying = AirSupport.ORDER.filter { AirSupport.applies(it).evaluate(input) == Verdict.Allowed }
            assertEquals(listOf(former(s)), applying, "$s")
        }
        assertEquals(2 * 2 * AirOrder.entries.size * 16, all.size)
    }

    @Test fun `a squadless gunship with a target stands off from it`() {
        val s = Situation(order = AirOrder.NONE, target = true)
        assertEquals(listOf(FlightDuty(pilot, FlightMode.STANDOFF, target), GunDuty(gunner, target)), duties(s))
    }

    @Test fun `the gunner keeps the aircraft's target while it breaks off`() {
        assertEquals(listOf(FlightDuty(pilot, FlightMode.RETIRE), GunDuty(gunner, target)), duties(Situation(fit = false, target = true)))
    }

    @Test fun `an aircraft alone in its squad is flown as its support`() {
        val squad = AgentId(AgentLevel.SQUAD, UUID(0, 7))
        val mission = SquadMission(squad, emptyList(), air = listOf(aircraft))
        val plan = (HtnPlanningSession(TacticalDomain.squad, PlanOwner(squad, UUID(1, 2)), TacticalDomain.SQUAD.call(mission), FactSnapshot(squad, 0)).advance(40) as PlanningResult.Ready).plan
        assertEquals(listOf(aircraft.agent), plan.tasks.map { (it.spec.action.parameters as DelegationTask).child })
    }
}

package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** The mortar crew's plan (#258): exactly one method per situation, and the squad's part in it. */
class FireSupportDomainTest {
    private val operator = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val loader = AgentId(AgentLevel.NPC, UUID(0, 2))
    private val crew = SupportCrew(AgentId(AgentLevel.TEAM, UUID(0, 9)), listOf(operator, loader))
    private val target = FireTarget(FireSource.OWN_SIGHTING, UUID(5, 5).toString())

    private data class Situation(
        val tube: TubeState = TubeState.DEPLOYED,
        val freeTube: Boolean = false,
        val relocate: Boolean = false,
        val outOfReach: Boolean = false,
        val shellsOut: Boolean = false,
        val fire: FireTarget? = null,
        val operator: AgentId? = null,
    )

    private fun facts(s: Situation): FactSnapshot {
        var f = FactSnapshot(crew.agent, 0).observe(CrewFacts.TUBE, s.tube).observe(CrewFacts.FREE_TUBE, s.freeTube)
            .observe(CrewFacts.RELOCATE, s.relocate).observe(CrewFacts.OUT_OF_REACH, s.outOfReach).observe(CrewFacts.SHELLS_OUT, s.shellsOut)
        if (s.relocate) f = f.observe(CrewFacts.RELOCATION, Point3(10.0, 1.0, 10.0))
        if (s.outOfReach) f = f.observe(CrewFacts.REACH_POINT, Point3(90.0, 1.0, 0.0))
        s.fire?.let { f = f.observe(CrewFacts.FIRE, it) }
        s.operator?.let { f = f.observe(CrewFacts.OPERATOR, it) }
        return f
    }

    private fun situations(): List<Situation> {
        val b = listOf(false, true)
        val axes: List<(Situation) -> List<Situation>> = listOf(
            { s -> TubeState.entries.map { s.copy(tube = it) } },
            { s -> b.map { s.copy(freeTube = it) } },
            { s -> b.map { s.copy(relocate = it) } },
            { s -> b.map { s.copy(outOfReach = it) } },
            { s -> b.map { s.copy(shellsOut = it) } },
            { s -> listOf(s, s.copy(fire = target)) },
        )
        return axes.fold(listOf(Situation())) { all, axis -> all.flatMap(axis) }
    }

    private fun plan(s: Situation) = (HtnPlanningSession(TacticalDomain.team, PlanOwner(crew.agent, UUID(1, 1)), FireSupport.TEAM_TASK.call(crew), facts(s)).advance(40) as PlanningResult.Ready).plan

    private fun duties(s: Situation) = plan(s).tasks.map { (it.spec.action.parameters as DelegationTask).task.parameters as CrewDuty }

    @Test fun `exactly one crew method applies for every situation, the first that wants to`() {
        val all = situations()
        all.forEach { s ->
            val input = facts(s)
            val applying = FireSupport.ORDER.filter { FireSupport.applies(it).evaluate(input) == Verdict.Allowed }
            assertEquals(1, applying.size, "$s: $applying")
            assertEquals(FireSupport.ORDER.first { FireSupport.wants(it, input) == Verdict.Allowed }, applying.single())
        }
        assertEquals(3 * 32, all.size)
    }

    @Test fun `a crew fires its target, the operator laying and the loader loading`() {
        val duties = duties(Situation(fire = target, operator = operator))
        assertEquals(listOf(CrewDuty(operator, CrewWork.OPERATE, target), CrewDuty(loader, CrewWork.LOAD)), duties)
        assertEquals("fire_mission/own_sighting", FireSupport.methodId(FireSupport.Plan.FIRE_MISSION, facts(Situation(fire = target))))
    }

    @Test fun `swapping the posts to fetch shells keeps the crew resupplying`() {
        val before = FireSupport.ORDER.single { FireSupport.applies(it).evaluate(facts(Situation(shellsOut = true, operator = operator))) == Verdict.Allowed }
        val after = FireSupport.ORDER.single { FireSupport.applies(it).evaluate(facts(Situation(shellsOut = true, operator = loader))) == Verdict.Allowed }
        assertEquals(FireSupport.Plan.RESUPPLY, before)
        assertEquals(before, after)
    }

    @Test fun `a tube sent elsewhere or out of reach is carried there, the others following`() {
        assertEquals(listOf(CrewDuty(operator, CrewWork.DISPLACE, to = Point3(10.0, 1.0, 10.0)), CrewDuty(loader, CrewWork.FOLLOW)), duties(Situation(relocate = true, fire = target, operator = operator)))
        assertEquals(Point3(90.0, 1.0, 0.0), duties(Situation(outOfReach = true, fire = target, operator = operator)).first().to)
    }

    @Test fun `a crew with no tube takes a free one, or stands in reserve`() {
        assertEquals(CrewWork.TAKE_TUBE, duties(Situation(tube = TubeState.NONE, freeTube = true)).first().work)
        assertEquals(CrewWork.RESERVE, duties(Situation(tube = TubeState.NONE)).first().work)
    }

    @Test fun `a man threatened at arm's length defends himself through his engage, never forward`() {
        val man = PlanOwner(operator, UUID(1, 1))
        val threatened = FactSnapshot(operator, 0).observe(CrewFacts.THREATENED, true)
        // Engage reads facts this snapshot leaves out: the plan waits on them, it never serves the tube.
        val first = HtnPlanningSession(TacticalDomain.npc, man, FireSupport.NPC_TASK.call(CrewDuty(operator, CrewWork.OPERATE)), threatened).advance(40)
        assertTrue(first is PlanningResult.WaitingForFacts, "$first")
        val calm = FactSnapshot(operator, 0).observe(CrewFacts.THREATENED, false)
        val plan = (HtnPlanningSession(TacticalDomain.npc, man, FireSupport.NPC_TASK.call(CrewDuty(operator, CrewWork.OPERATE)), calm).advance(40) as PlanningResult.Ready).plan
        assertEquals(FireSupport.ACTION, plan.tasks.single().spec.action.key)
    }

    @Test fun `a squad of crews alone only supports, through its crews' team agents`() {
        val squad = AgentId(AgentLevel.SQUAD, UUID(0, 7))
        val only = SquadMission(squad, emptyList(), listOf(crew))
        val plan = (HtnPlanningSession(TacticalDomain.squad, PlanOwner(squad, UUID(1, 1)), TacticalDomain.SQUAD.call(only), FactSnapshot(squad, 0)).advance(40) as PlanningResult.Ready).plan
        assertEquals(listOf(crew.agent), plan.tasks.map { (it.spec.action.parameters as DelegationTask).child })
    }
}

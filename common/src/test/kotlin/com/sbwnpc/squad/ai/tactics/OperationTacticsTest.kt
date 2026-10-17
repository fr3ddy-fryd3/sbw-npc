package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.coordination.TacticalOrder
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.hypot

class OperationTacticsTest {
    private val objective = Point3(100.0, 64.0, 100.0)
    private fun id(n: Int) = UUID(0, n.toLong())
    private fun member(n: Int, x: Double, ready: Boolean = true) = AttackMember(id(n), Point3(x, 64.0, 100.0), ready)

    @Test fun `deploy assigns no attack roles`() {
        assertEquals(AttackRoles.NONE, OperationTactics.roles(AttackPhase.DEPLOY, objective, listOf(id(1)), listOf(member(1, 40.0)), listOf(objective), emptySet()))
    }

    @Test fun `assault places movers on distinct spots inside the objective`() {
        val movers = (1..12).map(::id)
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, movers, movers.indices.map { member(it + 1, 40.0) }, emptyList(), emptySet())
        assertEquals(movers.toSet(), roles.assault.keys)
        assertTrue(roles.clearing.isEmpty())
        assertEquals(12, roles.assault.values.toSet().size)
        roles.assault.values.forEach { assertTrue(hypot(it.x - objective.x, it.z - objective.z) <= 12.0) }
    }

    @Test fun `an assault on a held point closes in to fire instead of running onto it`() {
        // In-game 16.10: "an assault is not running right up, it is closing the range and pressing with fire".
        val defender = Point3(100.0, 64.0, 130.0)
        val movers = listOf(id(1), id(2))
        val members = listOf(AttackMember(id(1), Point3(100.0, 64.0, 30.0), true), AttackMember(id(2), Point3(100.0, 64.0, 120.0), true))
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, movers, members, listOf(defender), emptySet())
        assertTrue(roles.assault.isEmpty())
        assertEquals(Point3(100.0, 64.0, 80.0), roles.closing[id(1)])
        // Already this close, a man holds where he is.
        assertEquals(Point3(100.0, 64.0, 120.0), roles.closing[id(2)])
        val applied = OperationTactics.apply(SquadMission(AgentId(AgentLevel.SQUAD, id(100)), listOf(TeamMission(AgentId(AgentLevel.TEAM, id(101)), listOf(PositionIntent(AgentId(AgentLevel.NPC, id(1)), CombatDuty.MOVE, Point3(100.0, 64.0, 30.0), Point3(100.0, 64.0, 50.0), defender))))), roles).teams.single().members.single()
        assertEquals(CombatDuty.FIRE, applied.duty)
        assertEquals(Point3(100.0, 64.0, 80.0), applied.anchor)
        assertFalse(applied.assault)
    }

    @Test fun `the closing post is measured from where the assault began`() {
        val defender = Point3(100.0, 64.0, 130.0)
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, listOf(id(1)), listOf(AttackMember(id(1), Point3(100.0, 64.0, 70.0), true)), listOf(defender), emptySet(), mapOf(id(1) to Point3(100.0, 64.0, 30.0)))
        assertEquals(Point3(100.0, 64.0, 80.0), roles.closing[id(1)])
    }

    @Test fun `with no defender left at the point the assault occupies it`() {
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, listOf(id(1)), listOf(member(1, 40.0)), listOf(Point3(100.0, 64.0, 200.0)), emptySet())
        assertTrue(roles.closing.isEmpty())
        assertEquals(setOf(id(1)), roles.assault.keys)
    }

    @Test fun `told to occupy, a closed-in assault takes the point despite defenders`() {
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, listOf(id(1)), listOf(member(1, 40.0)), listOf(Point3(100.0, 64.0, 130.0)), emptySet(), occupy = true)
        assertTrue(roles.closing.isEmpty())
        assertEquals(setOf(id(1)), roles.assault.keys)
    }

    @Test fun `the rear guard joins an assault`() {
        val rear = PositionIntent(AgentId(AgentLevel.NPC, id(1)), CombatDuty.REAR_GUARD, Point3(40.0, 64.0, 100.0), Point3(20.0, 64.0, 100.0), objective)
        val mission = SquadMission(AgentId(AgentLevel.SQUAD, id(100)), listOf(TeamMission(AgentId(AgentLevel.TEAM, id(101)), listOf(rear))))
        val post = Point3(70.0, 64.0, 100.0)
        val applied = OperationTactics.apply(mission, AttackRoles(closing = mapOf(id(1) to post))).teams.single().members.single()
        assertEquals(CombatDuty.FIRE, applied.duty)
        assertEquals(post, applied.anchor)
    }

    @Test fun `clearing sends the nearest ready members and keeps the team stable`() {
        val members = (1..8).map { member(it, 100.0 + it * 2) } + member(9, 150.0, ready = false)
        val straggler = Point3(160.0, 64.0, 100.0)
        val roles = OperationTactics.roles(AttackPhase.CLEAR, objective, (1..8).map(::id), members, listOf(straggler), emptySet())
        assertEquals(setOf(id(7), id(8)), roles.clearing.keys)
        assertTrue(roles.clearing.values.all { it == straggler })
        assertTrue(roles.assault.keys.none { it in roles.clearing })
        val again = OperationTactics.roles(AttackPhase.CLEAR, objective, (1..8).map(::id), members, listOf(straggler), setOf(id(1), id(2)))
        assertEquals(setOf(id(1), id(2)), again.clearing.keys)
    }

    @Test fun `apply rewrites only the members with a role`() {
        val intent = { n: Int -> PositionIntent(AgentId(AgentLevel.NPC, id(n)), CombatDuty.FIRE, Point3(40.0, 64.0, 100.0), Point3(60.0, 64.0, 100.0), objective, requireShelter = true) }
        val mission = SquadMission(AgentId(AgentLevel.SQUAD, id(100)), listOf(TeamMission(AgentId(AgentLevel.TEAM, id(101)), listOf(intent(1), intent(2), intent(3)))))
        val slot = Point3(104.0, 64.0, 100.0)
        val target = Point3(160.0, 64.0, 100.0)
        val applied = OperationTactics.apply(mission, AttackRoles(mapOf(id(1) to slot), mapOf(id(2) to target))).teams.single().members
        assertEquals(CombatDuty.MOVE, applied[0].duty)
        assertEquals(slot, applied[0].anchor)
        assertTrue(applied[0].assault && !applied[0].requireShelter)
        assertEquals(CombatDuty.SEARCH, applied[1].duty)
        assertEquals(target, applied[1].inspection)
        assertEquals(intent(3), applied[2])
        assertSame(mission, OperationTactics.apply(mission, AttackRoles.NONE))
    }

    private val here = Point3(40.0, 64.0, 100.0)
    private fun duty(order: TacticalOrder = TacticalOrder.ATTACK, proposal: FrontProposal = FrontProposal(CombatDuty.FIRE, Point3(60.0, 64.0, 100.0), null), inContact: Boolean = true, scout: Boolean = false, observer: Boolean = false, reachedMove: Boolean = false, wasFiring: Boolean = false, elsewhere: Boolean = false) = OperationTactics.duty(DutyInputs(order, here, proposal, objective, Point3(90.0, 64.0, 100.0), inContact, elsewhere, scout, observer, hasPatrolRoute = false, reachedMove = reachedMove, wasFiring = wasFiring), fireReach = 40.0, approachOffset = 10.0)

    @Test fun `the front proposal applies only in a fight on attack`() {
        assertEquals(DutyChoice(CombatDuty.FIRE, Point3(60.0, 64.0, 100.0)), duty())
        assertEquals(DutyChoice(CombatDuty.FOLLOW_ORDER, objective), duty(inContact = false))
        assertEquals(DutyChoice(CombatDuty.FOLLOW_ORDER, objective), duty(order = TacticalOrder.MOVE))
        assertEquals(DutyChoice(CombatDuty.FIRE, here), duty(order = TacticalOrder.DEFEND))
        assertEquals(DutyChoice(CombatDuty.OBSERVE, objective), duty(order = TacticalOrder.DEFEND, inContact = false, observer = true))
        assertEquals(CombatDuty.FOLLOW_ORDER, duty(elsewhere = true, scout = true).duty)
        assertEquals(DutyChoice(CombatDuty.SEARCH, Point3(90.0, 64.0, 100.0)), duty(scout = true))
    }

    @Test fun `a far fire post is approached as movement short of it`() {
        val far = duty(proposal = FrontProposal(CombatDuty.FIRE, Point3(100.0, 64.0, 100.0), null))
        assertEquals(CombatDuty.MOVE, far.duty)
        assertEquals(Point3(90.0, 64.0, 100.0), far.anchor)
    }

    @Test fun `a mover keeps firing once it has reached its post or fired`() {
        val move = FrontProposal(CombatDuty.MOVE, Point3(70.0, 64.0, 100.0), null)
        assertEquals(DutyChoice(CombatDuty.MOVE, Point3(70.0, 64.0, 100.0)), duty(proposal = move))
        assertEquals(DutyChoice(CombatDuty.FIRE, here), duty(proposal = move, reachedMove = true))
        assertEquals(DutyChoice(CombatDuty.FIRE, here), duty(proposal = move, wasFiring = true))
    }
}

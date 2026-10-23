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

    @Test fun `going onto a point a defender still stands at, no man comes nearer him than the standoff`() {
        val movers = (1..12).map(::id)
        val defender = Point3(103.0, 64.0, 98.0)
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, movers, movers.indices.map { member(it + 1, 40.0) }, emptyList(), emptySet(), standoffFrom = listOf(defender))
        assertEquals(movers.toSet(), roles.assault.keys)
        roles.assault.values.forEach {
            assertTrue(hypot(it.x - defender.x, it.z - defender.z) >= OperationTactics.ASSAULT_STANDOFF - 1.0E-6, "slot $it is beside the defender")
            // Still near enough the point to count as occupying it.
            assertTrue(hypot(it.x - objective.x, it.z - objective.z) <= 24.0, "slot $it is off the point")
        }
    }

    @Test fun `a clearing man already within the closing distance holds where he is`() {
        val target = Point3(118.0, 64.0, 100.0)
        val near = Point3(100.0, 64.0, 100.0)
        assertEquals(ClosingPost(near, target), OperationTactics.closingPost(near, listOf(target)))
    }

    @Test fun `an assault on a held point closes in to fire instead of running onto it`() {
        // In-game 16.10: "an assault is not running right up, it is closing the range and pressing with fire".
        val defender = Point3(100.0, 64.0, 130.0)
        val movers = listOf(id(1), id(2))
        val members = listOf(AttackMember(id(1), Point3(100.0, 64.0, 30.0), true), AttackMember(id(2), Point3(100.0, 64.0, 120.0), true))
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, movers, members, listOf(defender), emptySet())
        assertTrue(roles.assault.isEmpty())
        assertEquals(ClosingPost(Point3(100.0, 64.0, 80.0), defender), roles.closing[id(1)])
        // Already this close, a man holds where he is.
        assertEquals(ClosingPost(Point3(100.0, 64.0, 120.0), defender), roles.closing[id(2)])
        val applied = OperationTactics.apply(SquadMission(AgentId(AgentLevel.SQUAD, id(100)), listOf(TeamMission(AgentId(AgentLevel.TEAM, id(101)), listOf(PositionIntent(AgentId(AgentLevel.NPC, id(1)), CombatDuty.MOVE, Point3(100.0, 64.0, 30.0), Point3(100.0, 64.0, 50.0), defender))))), roles).teams.single().members.single()
        assertEquals(CombatDuty.FIRE, applied.duty)
        assertEquals(Point3(100.0, 64.0, 80.0), applied.anchor)
        // He fires at the defender his post was measured from.
        assertEquals(defender, applied.focus)
        // Inside the role's clearance, or a rifleman never reaches a post 20-30 blocks out (#54).
        assertTrue(applied.assault)
    }

    @Test fun `the closing post is measured from where the assault began`() {
        val defender = Point3(100.0, 64.0, 130.0)
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, listOf(id(1)), listOf(AttackMember(id(1), Point3(100.0, 64.0, 70.0), true)), listOf(defender), emptySet(), mapOf(id(1) to Point3(100.0, 64.0, 30.0)))
        assertEquals(ClosingPost(Point3(100.0, 64.0, 80.0), defender), roles.closing[id(1)])
    }

    @Test fun `with no defender left at the point the assault occupies it`() {
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, listOf(id(1)), listOf(member(1, 40.0)), listOf(Point3(100.0, 64.0, 200.0)), emptySet())
        assertTrue(roles.closing.isEmpty())
        assertEquals(setOf(id(1)), roles.assault.keys)
    }

    @Test fun `the rear guard joins an assault`() {
        val rear = PositionIntent(AgentId(AgentLevel.NPC, id(1)), CombatDuty.REAR_GUARD, Point3(40.0, 64.0, 100.0), Point3(20.0, 64.0, 100.0), objective)
        val mission = SquadMission(AgentId(AgentLevel.SQUAD, id(100)), listOf(TeamMission(AgentId(AgentLevel.TEAM, id(101)), listOf(rear))))
        val post = Point3(70.0, 64.0, 100.0)
        val applied = OperationTactics.apply(mission, AttackRoles(closing = mapOf(id(1) to ClosingPost(post, objective)))).teams.single().members.single()
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
        // A clearing man fires at the enemy from a post short of him, never from the enemy's spot.
        assertEquals(CombatDuty.FIRE, applied[1].duty)
        assertEquals(target, applied[1].focus)
        assertTrue(kotlin.math.hypot(applied[1].anchor.x - target.x, applied[1].anchor.z - target.z) >= OperationTactics.CLOSE_DISTANCE)
        assertTrue(applied[1].assault)
        assertEquals(intent(3), applied[2])
        assertSame(mission, OperationTactics.apply(mission, AttackRoles.NONE))
    }

    private val here = Point3(40.0, 64.0, 100.0)
    private val nearObjective = Point3(80.0, 64.0, 100.0)

    /** A sentry's duty under [order] where he stands [at], [observing] already or not. */
    private fun sentry(order: TacticalOrder, at: Point3, inContact: Boolean, observing: Boolean = false) = OperationTactics.duty(DutyInputs(order, at, FrontProposal(CombatDuty.FIRE, at, null), objective, null, inContact, elsewhere = false, scout = false, heightRole = true, primaryAmmo = true, hasPatrolRoute = false, reachedMove = false, wasFiring = false, wasObserving = observing), fireReach = 40.0, approachOffset = 10.0)
    private fun duty(order: TacticalOrder = TacticalOrder.ATTACK, proposal: FrontProposal = FrontProposal(CombatDuty.FIRE, Point3(60.0, 64.0, 100.0), null), inContact: Boolean = true, scout: Boolean = false, heightRole: Boolean = false, primaryAmmo: Boolean = true, reachedMove: Boolean = false, wasFiring: Boolean = false, elsewhere: Boolean = false) = OperationTactics.duty(DutyInputs(order, here, proposal, objective, Point3(90.0, 64.0, 100.0), inContact, elsewhere, scout, heightRole, primaryAmmo, hasPatrolRoute = false, reachedMove = reachedMove, wasFiring = wasFiring), fireReach = 40.0, approachOffset = 10.0)

    @Test fun `a following squad in a fight runs to its places by bounds while the other half fires`() {
        val follower = DutyInputs(TacticalOrder.DEFEND, here, FrontProposal(CombatDuty.FIRE, here, null), objective, null, inContact = true, elsewhere = false, scout = false, heightRole = false, primaryAmmo = true, hasPatrolRoute = false, reachedMove = false, wasFiring = false, following = true)
        val slot = Point3(objective!!.x + 3.0, objective.y, objective.z)
        val running = OperationTactics.duty(follower.copy(followBound = true, followSlot = slot), fireReach = 40.0, approachOffset = 10.0)
        assertEquals(CombatDuty.MOVE, running.duty)
        assertEquals(slot, running.anchor)
        val covering = OperationTactics.duty(follower.copy(followBound = false, followSlot = slot), fireReach = 40.0, approachOffset = 10.0)
        assertEquals(CombatDuty.FIRE, covering.duty)
        assertEquals(here, covering.anchor)
        // Not following: a bound means nothing.
        assertEquals(CombatDuty.FIRE, OperationTactics.duty(follower.copy(following = false, followBound = true, followSlot = slot), fireReach = 40.0, approachOffset = 10.0).duty)
        // A player in a vehicle is followed by the ride.
        assertEquals(CombatDuty.FOLLOW_ORDER, OperationTactics.duty(follower.copy(followBound = true, followSlot = slot, followRide = true), fireReach = 40.0, approachOffset = 10.0).duty)
    }

    @Test fun `halves take turns between 12 and 32 blocks, nobody runs nearer and everybody further`() {
        assertTrue(OperationTactics.followBound(half = 0, now = 0, toLeader = 20.0))
        assertFalse(OperationTactics.followBound(half = 1, now = 0, toLeader = 20.0))
        assertFalse(OperationTactics.followBound(half = 0, now = 100, toLeader = 20.0))
        assertTrue(OperationTactics.followBound(half = 1, now = 100, toLeader = 20.0))
        assertFalse(OperationTactics.followBound(half = 0, now = 0, toLeader = 10.0))
        assertTrue(OperationTactics.followBound(half = 1, now = 0, toLeader = 40.0))
    }

    @Test fun `a sentry far from his objective marches there before he takes a height post`() {
        assertEquals(DutyChoice(CombatDuty.FOLLOW_ORDER, objective), sentry(TacticalOrder.DEFEND, here, inContact = false))
        assertEquals(DutyChoice(CombatDuty.FOLLOW_ORDER, objective), sentry(TacticalOrder.PATROL, here, inContact = false))
    }

    @Test fun `a sentry on the edge of his post search does not flip back to marching`() {
        val edge = Point3(objective!!.x - 28.0, objective.y, objective.z)
        assertEquals(CombatDuty.FOLLOW_ORDER, sentry(TacticalOrder.DEFEND, edge, inContact = false).duty)
        assertEquals(CombatDuty.OBSERVE, sentry(TacticalOrder.DEFEND, edge, inContact = false, observing = true).duty)
        val outside = Point3(objective.x - 33.0, objective.y, objective.z)
        assertEquals(CombatDuty.FOLLOW_ORDER, sentry(TacticalOrder.DEFEND, outside, inContact = false, observing = true).duty)
    }

    @Test fun `a gunner following his player takes no height post`() {
        val sentry = DutyInputs(TacticalOrder.DEFEND, nearObjective, FrontProposal(CombatDuty.FIRE, nearObjective, null), objective, null, inContact = false, elsewhere = false, scout = false, heightRole = true, primaryAmmo = true, hasPatrolRoute = false, reachedMove = false, wasFiring = false)
        assertEquals(CombatDuty.OBSERVE, OperationTactics.duty(sentry, fireReach = 40.0, approachOffset = 10.0).duty)
        assertEquals(CombatDuty.FOLLOW_ORDER, OperationTactics.duty(sentry.copy(following = true), fireReach = 40.0, approachOffset = 10.0).duty)
        assertEquals(CombatDuty.FIRE, OperationTactics.duty(sentry.copy(following = true, inContact = true), fireReach = 40.0, approachOffset = 10.0).duty)
    }

    @Test fun `the front proposal applies only in a fight on attack`() {
        assertEquals(DutyChoice(CombatDuty.FIRE, Point3(60.0, 64.0, 100.0)), duty())
        assertEquals(DutyChoice(CombatDuty.FOLLOW_ORDER, objective), duty(inContact = false))
        assertEquals(DutyChoice(CombatDuty.FOLLOW_ORDER, objective), duty(order = TacticalOrder.MOVE))
        assertEquals(DutyChoice(CombatDuty.FIRE, here), duty(order = TacticalOrder.DEFEND))
        assertEquals(DutyChoice(CombatDuty.OBSERVE, objective), sentry(TacticalOrder.DEFEND, nearObjective, inContact = false))
        assertEquals(DutyChoice(CombatDuty.OBSERVE, objective), sentry(TacticalOrder.DEFEND, nearObjective, inContact = true))
        assertEquals(DutyChoice(CombatDuty.FIRE, Point3(60.0, 64.0, 100.0)), duty(heightRole = true))
        assertEquals(DutyChoice(CombatDuty.OBSERVE, objective), sentry(TacticalOrder.PATROL, nearObjective, inContact = true))
        assertEquals(DutyChoice(CombatDuty.FIRE, here), duty(order = TacticalOrder.DEFEND, heightRole = true, primaryAmmo = false))
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

    @Test fun `a flanker keeps walking to his next waypoint even after he fired`() {
        val waypoint = Point3(30.0, 64.0, 140.0)
        val inputs = DutyInputs(TacticalOrder.ATTACK, here, FrontProposal(CombatDuty.MOVE, waypoint, null), objective, null, inContact = true, elsewhere = false, scout = false, heightRole = false, primaryAmmo = true, hasPatrolRoute = false, reachedMove = true, wasFiring = true, flanking = true)
        assertEquals(DutyChoice(CombatDuty.MOVE, waypoint), OperationTactics.duty(inputs, fireReach = 40.0, approachOffset = 10.0))
    }

    @Test fun `a mover in his slot's area fires from where he is even without a post`() {
        val near = FrontProposal(CombatDuty.MOVE, Point3(here.x + 6.0, here.y, here.z), null)
        assertEquals(DutyChoice(CombatDuty.FIRE, here), duty(proposal = near))
        val far = FrontProposal(CombatDuty.MOVE, Point3(here.x + 20.0, here.y, here.z), null)
        assertEquals(CombatDuty.MOVE, duty(proposal = far).duty)
    }
}

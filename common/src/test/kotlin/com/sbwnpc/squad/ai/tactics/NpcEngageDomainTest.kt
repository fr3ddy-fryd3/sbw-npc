package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** The NPC domain's engage (#251 step B): in contact, exactly one move applies, the first of Engage.ORDER that wants to. */
class NpcEngageDomainTest {
    private val actor = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val owner = PlanOwner(actor, UUID(1, 1))
    private val follower = PositionIntent(actor, CombatDuty.FOLLOW_ORDER, Point3(0.0, 1.0, 0.0), Point3(0.0, 1.0, 0.0), Point3(60.0, 1.0, 0.0))

    /** One engage situation; every fact the moves read, so a test names only what it changes. */
    private data class Situation(
        val contact: Boolean = true,
        val sidearm: Boolean = false,
        val keepAway: Boolean = false,
        val inReach: Boolean = false,
        val clearShot: Boolean = false,
        val holding: Boolean = false,
        val outside: Boolean = false,
        val mayAdvance: Boolean = false,
        val allyChecks: Int = 0,
        val hullChecks: Int = 0,
        val firstShootable: Long = Engage.NO_SIGHT,
        val shots: Int = 0,
        val units: Int = 0,
    )

    private fun facts(s: Situation, now: Long = 1000) =
        FactSnapshot(actor, now).observe(TacticalFacts.MOVABLE, true).observe(TacticalFacts.COVER, true)
            .observe(TacticalFacts.UNDER_FIRE, false).observe(TacticalFacts.DIGS, 0).observe(TacticalFacts.RESUPPLYING, false)
            .observe(TacticalFacts.IN_CONTACT, s.contact).observe(TacticalFacts.SIDEARM, s.sidearm).observe(TacticalFacts.KEEP_AWAY, s.keepAway)
            .observe(TacticalFacts.IN_REACH, s.inReach).observe(TacticalFacts.CLEAR_SHOT, s.clearShot).observe(TacticalFacts.HOLDING_ORDER, s.holding)
            .observe(TacticalFacts.OUTSIDE_AREA, s.outside).observe(TacticalFacts.MAY_ADVANCE, s.mayAdvance)
            .observe(TacticalFacts.LANE_ALLY_CHECKS, s.allyChecks).observe(TacticalFacts.LANE_HULL_CHECKS, s.hullChecks)
            .observe(TacticalFacts.FIRST_SHOOTABLE_AT, s.firstShootable).observe(TacticalFacts.SHOTS_AT_TARGET, s.shots).observe(TacticalFacts.AUTOMATIC, true)
            .observe(TacticalFacts.ENGAGE_UNITS, s.units)

    private fun unit(input: FactSnapshot, call: PositionIntent = follower): EngageUnit {
        val plan = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(call), input).advance(40) as PlanningResult.Ready).plan
        val action = plan.tasks.single().spec.action
        assertEquals(TacticalDomain.ENGAGE, action.key)
        return action.parameters as EngageUnit
    }

    private fun move(s: Situation) = unit(facts(s)).move

    @Test fun `a follower out of contact carries out his order, not an engage`() {
        val plan = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(follower), facts(Situation(contact = false))).advance(40) as PlanningResult.Ready).plan
        assertEquals(TacticalDomain.POSITION, plan.tasks.single().spec.action.key)
    }

    @Test fun `in contact a follower holds a spot within reach, closes in with a shot and joins the firing area without one`() {
        assertEquals(EngageMove.HOLD_SPOT, move(Situation(inReach = true, clearShot = true)))
        assertEquals(EngageMove.CLOSE_IN, move(Situation(clearShot = true)))
        assertEquals(EngageMove.JOIN_FIRE, move(Situation()))
    }

    @Test fun `on a sidearm no move takes him forward`() {
        assertEquals(EngageMove.HOLD_SPOT, move(Situation(sidearm = true, clearShot = true)))
        assertEquals(EngageMove.HOLD_SPOT, move(Situation(sidearm = true)))
        assertEquals(EngageMove.HOLD_SPOT, move(Situation(sidearm = true, holding = true, mayAdvance = true)))
        assertEquals(EngageMove.RETURN_TO_AREA, move(Situation(sidearm = true, holding = true, outside = true)))
    }

    @Test fun `a fresh target gets an opening burst first, until the window or the burst is over`() {
        assertEquals(EngageMove.OPEN_FIRE, move(Situation(clearShot = true, firstShootable = 990)))
        assertEquals(EngageMove.CLOSE_IN, move(Situation(clearShot = true, firstShootable = 1000 - Engage.OPENING_WINDOW)))
        assertEquals(EngageMove.CLOSE_IN, move(Situation(clearShot = true, firstShootable = 990, shots = Engage.OPENING_ROUNDS_AUTOMATIC)))
    }

    @Test fun `an ally in the lane for two checks makes him step aside, one check moves nobody`() {
        assertEquals(EngageMove.CLOSE_IN, move(Situation(clearShot = true, allyChecks = Engage.LANE_DWELL_CHECKS - 1)))
        assertEquals(EngageMove.SIDESTEP, move(Situation(clearShot = true, allyChecks = Engage.LANE_DWELL_CHECKS)))
        assertEquals(EngageMove.RESEARCH_SPOT, move(Situation(clearShot = true, hullChecks = Engage.LANE_DWELL_CHECKS)))
    }

    @Test fun `in the open under fire his engage never takes him forward`() {
        val call = follower.copy(duty = CombatDuty.FIRE)
        val open = facts(Situation(clearShot = true)).observe(TacticalFacts.UNDER_FIRE, true).observe(TacticalFacts.FIRE_SECTOR, FireSector.of(60.0, 0.0))
            .observe(TacticalFacts.COVER_FAILURE, CoverFailure(FireSector.of(60.0, 0.0), 1)).observe(TacticalFacts.DIG_POSSIBLE, false).observe(TacticalFacts.ENGAGED, true)
        val unit = unit(open, call)
        assertEquals(EngageMove.HOLD_SPOT, unit.move)
    }

    @Test fun `a unit's identity carries his weapon, so a switch is a new action`() {
        val primary = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(follower), facts(Situation(inReach = true))).advance(40) as PlanningResult.Ready).plan
        val pistol = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(follower), facts(Situation(inReach = true, sidearm = true))).advance(40) as PlanningResult.Ready).plan
        assertNotEquals(primary.tasks.single().spec.identity, pistol.tasks.single().spec.identity)
    }

    /** Every situation the moves tell apart: each fact they read on both sides of what they test. */
    private fun situations(): List<Situation> {
        val booleans = listOf(false, true)
        val checks = listOf(0, Engage.LANE_DWELL_CHECKS)
        val axes: List<(Situation) -> List<Situation>> = listOf(
            { s -> booleans.map { s.copy(sidearm = it) } },
            { s -> booleans.map { s.copy(keepAway = it) } },
            { s -> booleans.map { s.copy(inReach = it) } },
            { s -> booleans.map { s.copy(clearShot = it) } },
            { s -> booleans.map { s.copy(holding = it) } },
            { s -> booleans.map { s.copy(outside = it) } },
            { s -> booleans.map { s.copy(mayAdvance = it) } },
            { s -> checks.map { s.copy(allyChecks = it) } },
            { s -> checks.map { s.copy(hullChecks = it) } },
            { s -> listOf(s, s.copy(firstShootable = 990)) },
        )
        return axes.fold(listOf(Situation())) { all, axis -> all.flatMap(axis) }
    }

    @Test fun `exactly one move applies for every combination of facts, the first of the order that wants to`() {
        val cases = situations().flatMap { s -> listOf(true, false).map { s to EngageTask(actor, it) } }
        cases.forEach { (s, task) ->
            val input = facts(s)
            val applying = Engage.ORDER.filter { Engage.applies(it, task).evaluate(input) == Verdict.Allowed }
            assertEquals(1, applying.size, "$s $task: $applying")
            assertEquals(Engage.ORDER.first { Engage.wants(it, task, input) == Verdict.Allowed }, applying.single())
            if (!task.forward || s.sidearm) assertTrue(applying.single() !in Engage.FORWARD, "$s $task: ${applying.single()}")
        }
        assertEquals(1 shl 11, cases.size)
    }

    @Test fun `a clear shot flickering every 20 ticks under the 40-tick hold switches his move at most once`() {
        // The fact builder holds CLEAR_SHOT for Engage.CLEAR_SHOT_TICKS after the last clear shot;
        // replayed here over a shot clear for 20 ticks and hidden for 20.
        var lastClear = Long.MIN_VALUE / 2
        val chosen = ArrayList<EngageMove>()
        for (now in 0L until 200L) {
            if ((now / 20) % 2 == 0L) lastClear = now
            val clear = now - lastClear < Engage.CLEAR_SHOT_TICKS
            chosen += requireNotNull(Engage.choose(EngageTask(actor), facts(Situation(clearShot = clear), now)))
        }
        val switches = chosen.zipWithNext().count { (a, b) -> a != b }
        assertTrue(switches <= 1, "switched $switches times: $chosen")
    }

    @Test fun `a unit is over when its executor finished it, or its opening fire or the lane block is over`() {
        val close = EngageUnit(actor, EngageMove.CLOSE_IN, 0, false)
        assertEquals(Verdict.Rejected("unit_running"), Engage.unitOver(close).evaluate(facts(Situation())))
        assertEquals(Verdict.Allowed, Engage.unitOver(close).evaluate(facts(Situation(units = 1))))
        assertEquals(Verdict.Allowed, Engage.unitOver(EngageUnit(actor, EngageMove.OPEN_FIRE, 0, false)).evaluate(facts(Situation(firstShootable = 990, shots = Engage.OPENING_ROUNDS_AUTOMATIC))))
        assertEquals(Verdict.Allowed, Engage.unitOver(EngageUnit(actor, EngageMove.SIDESTEP, 0, false)).evaluate(facts(Situation(allyChecks = 0))))
    }
}

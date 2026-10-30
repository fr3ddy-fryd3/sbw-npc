package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** The NPC domain's answer to fire (#251): exactly one family of methods applies to any man. */
class NpcUnderFireDomainTest {
    private val actor = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val owner = PlanOwner(actor, UUID(1, 1))
    private val intent = PositionIntent(actor, CombatDuty.FIRE, Point3(0.0, 1.0, 0.0), Point3(10.0, 1.0, 0.0), Point3(60.0, 1.0, 0.0))
    private val sector = FireSector.of(60.0, 0.0)

    @Suppress("LongParameterList") // one argument per fact the methods read
    private fun facts(underFire: Boolean, failure: Boolean = false, dig: Boolean = false, engaged: Boolean = false, digs: Int = 0, hit: Boolean = false, healed: Boolean = false, cycles: Int = 0): FactSnapshot {
        var facts = FactSnapshot(actor, 1).observe(TacticalFacts.MOVABLE, true).observe(TacticalFacts.COVER, true)
            .observe(TacticalFacts.UNDER_FIRE, underFire).observe(TacticalFacts.ENGAGED, engaged).observe(TacticalFacts.DIG_POSSIBLE, dig)
            .observe(TacticalFacts.DIGS, digs).observe(TacticalFacts.HIT_IN_HOLE, hit).observe(TacticalFacts.HEALED, healed).observe(TacticalFacts.COVER_CYCLES, cycles)
        if (underFire) facts = facts.observe(TacticalFacts.FIRE_SECTOR, sector)
        if (failure) facts = facts.observe(TacticalFacts.COVER_FAILURE, CoverFailure(sector, 1))
        return facts
    }

    private fun plan(input: FactSnapshot, call: PositionIntent = intent) = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(call), input).advance(40) as PlanningResult.Ready).plan

    private fun first(input: FactSnapshot, call: PositionIntent = intent) = plan(input, call).tasks.first().spec.action

    @Test fun `without fire he carries out his duty`() {
        assertEquals(TacticalDomain.POSITION, first(facts(underFire = false)).key)
    }

    @Test fun `under fire with cover left he moves to cover and then dwells and peeks`() {
        val plan = plan(facts(underFire = true))
        assertEquals(listOf(TacticalDomain.COVER_MOVE, TacticalDomain.COVER_CYCLE), plan.tasks.map { it.spec.action.key })
        assertEquals(CoverMove(actor, sector, fallBack = false), plan.tasks.first().spec.action.parameters)
    }

    @Test fun `with no cover he digs in when he can, else holds when engaged, else falls back`() {
        assertEquals(TacticalDomain.DIG, first(facts(underFire = true, failure = true, dig = true, engaged = true)).key)
        val hold = first(facts(underFire = true, failure = true, engaged = true))
        assertEquals(TacticalDomain.POSITION, hold.key)
        assertTrue((hold.parameters as PositionIntent).hold)
        val fallBack = first(facts(underFire = true, failure = true))
        assertEquals(CoverMove(actor, sector, fallBack = true), fallBack.parameters)
    }

    @Test fun `a cover failure from another sector leaves cover to search for`() {
        val elsewhere = facts(underFire = true).observe(TacticalFacts.COVER_FAILURE, CoverFailure(FireSector.of(-60.0, 0.0), 1))
        assertEquals(TacticalDomain.COVER_MOVE, first(elsewhere).key)
    }

    @Test fun `an assault and a man moving for his order keep on under fire`() {
        assertEquals(TacticalDomain.POSITION, first(facts(underFire = true), intent.copy(assault = true)).key)
        assertEquals(TacticalDomain.POSITION, first(facts(underFire = true), intent.copy(ordered = true)).key)
    }

    @Test fun `in his hole he leaves once healed, digs deeper when hit again, else holds`() {
        assertEquals(TacticalDomain.LEAVE_HOLE, first(facts(underFire = true, digs = 1, healed = true)).key)
        assertEquals(TacticalDomain.LEAVE_HOLE, first(facts(underFire = false, digs = 1)).key)
        assertEquals(TacticalDomain.DIG, first(facts(underFire = true, digs = 1, hit = true, dig = true)).key)
        assertEquals(TacticalDomain.HOLD_HOLE, first(facts(underFire = true, digs = UnderFire.MAX_DIGS, hit = true, dig = true)).key)
        assertEquals(TacticalDomain.HOLD_HOLE, first(facts(underFire = false, digs = 1, engaged = true)).key)
    }

    @Test fun `exactly one method of the man's task applies for every combination of facts`() {
        val booleans = listOf(false, true)
        val flags = booleans.flatMap { fire -> booleans.flatMap { failure -> booleans.flatMap { dig -> booleans.map { listOf(fire, failure && fire, dig, it) } } } }
        val combinations = flags.flatMap { f -> (0..UnderFire.MAX_DIGS).flatMap { digs -> booleans.flatMap { hit -> booleans.map { healed -> facts(f[0], f[1], f[2], f[3], digs, hit, healed) } } } }
        val calls = listOf(intent, intent.copy(assault = true), intent.copy(ordered = true))
        calls.forEach { call ->
            combinations.forEach { input ->
                val allowed = ArrayList<String>()
                HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(call), input, event = { if (it.node == "root" && it.verdict == Verdict.Allowed && it.method != null) allowed += it.method!! }).advance(40)
                assertEquals(1, allowed.distinct().size, "$call $input: $allowed")
            }
        }
    }

    @Test fun `a man moving for his order stays in his hole until he would leave it`() {
        val ordered = intent.copy(ordered = true)
        assertEquals(TacticalDomain.HOLD_HOLE, first(facts(underFire = true, digs = 1), ordered).key)
        assertEquals(TacticalDomain.LEAVE_HOLE, first(facts(underFire = true, digs = 1, healed = true), ordered).key)
    }

    @Test fun `a finished cover cycle changes the plan's input so he looks again`() {
        val before = plan(facts(underFire = true))
        assertFalse(before.inputUnchanged(facts(underFire = true, cycles = 1)))
    }
}

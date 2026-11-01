package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** A drone operator's strike (#259): the former launch gate and target order, and its place among his families. */
class DroneStrikeDomainTest {
    private val actor = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val owner = PlanOwner(actor, UUID(1, 1))
    private val intent = PositionIntent(actor, CombatDuty.FIRE, Point3(0.0, 1.0, 0.0), Point3(10.0, 1.0, 0.0), Point3(60.0, 1.0, 0.0))
    private fun point(n: Long) = StrikePoint(UUID(7, n), Point3(n * 10.0, 1.0, 0.0))

    private data class Situation(val airborne: Boolean = false, val flying: Boolean = false, val hold: DroneHold = DroneHold.NONE, val sources: Set<StrikeSource> = emptySet())

    private fun facts(s: Situation, base: FactSnapshot = FactSnapshot(actor, 1)): FactSnapshot {
        var f = base.observe(DroneFacts.AIRBORNE, s.airborne).observe(DroneFacts.HOLD, s.hold).observe(DroneFacts.SKY_OPEN, true)
            .observe(DroneFacts.OPTIONS, StrikeOptions(s.sources.associateWith { point(it.ordinal.toLong()) }))
        if (s.flying) f = f.observe(DroneFacts.FLYING, point(99))
        return f
    }

    private fun situations(): List<Situation> {
        val sourceSets = (0 until (1 shl StrikeSource.entries.size)).map { mask -> StrikeSource.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet() }
        return listOf(false, true).flatMap { airborne ->
            listOf(false, true).filter { airborne || !it }.flatMap { flying ->
                DroneHold.entries.flatMap { hold -> sourceSets.map { Situation(airborne, flying, hold, it) } }
            }
        }
    }

    /** The former DroneOperatorBehaviour: canPrepareLaunch + strikeTarget on foot, the flight and its retarget in the air. */
    private fun former(s: Situation): DroneStrike.Plan {
        val first = StrikeSource.entries.firstOrNull { it in s.sources }
        return when {
            s.airborne && s.flying -> DroneStrike.Plan.KEEP_FLYING
            s.airborne -> first?.let(::byStrike) ?: DroneStrike.Plan.FLY_HOME
            s.hold != DroneHold.NONE -> DroneStrike.Plan.STAND_BY
            else -> first?.let(::byStrike) ?: DroneStrike.Plan.STAND_BY
        }
    }

    private fun byStrike(source: StrikeSource) = DroneStrike.Plan.entries.first { it.source == source }

    @Test fun `exactly one method applies for every situation, the one the operator used to choose`() {
        situations().forEach { s ->
            val input = facts(s)
            assertEquals(listOf(former(s)), DroneStrike.ORDER.filter { DroneStrike.applies(it).evaluate(input) == Verdict.Allowed }, "$s")
        }
    }

    @Test fun `a drone in the air under a retreat finishes its run`() {
        assertEquals(DroneStrike.Plan.KEEP_FLYING, DroneStrike.choose(facts(Situation(airborne = true, flying = true, hold = DroneHold.RETREAT))))
    }

    @Test fun `a lost target is replaced by source order, else the drone flies home`() {
        val input = facts(Situation(airborne = true, sources = setOf(StrikeSource.REPORTED, StrikeSource.TEAM)))
        assertEquals(DroneStrike.Plan.STRIKE_TEAM, DroneStrike.choose(input))
        assertEquals(point(StrikeSource.TEAM.ordinal.toLong()), DroneStrike.duty(DroneStrike.Plan.STRIKE_TEAM, StrikeCall(actor), input).target)
        assertNull(DroneStrike.duty(DroneStrike.Plan.FLY_HOME, StrikeCall(actor), facts(Situation(airborne = true))).target)
    }

    @Test fun `a man is the same target wherever he stands`() {
        assertEquals(StrikePoint(UUID(1, 1), Point3(0.0, 0.0, 0.0)), StrikePoint(UUID(1, 1), Point3(5.0, 0.0, 5.0)))
        assertNotEquals(StrikePoint(null, Point3(0.0, 0.0, 0.0)), StrikePoint(null, Point3(5.0, 0.0, 5.0)))
    }

    private fun base(underFire: Boolean) =
        FactSnapshot(actor, 1).observe(TacticalFacts.MOVABLE, true).observe(TacticalFacts.COVER, true)
            .observe(TacticalFacts.UNDER_FIRE, underFire).observe(TacticalFacts.DIGS, 0).observe(TacticalFacts.ENGAGED, true).observe(TacticalFacts.IN_CONTACT, false)
            .let { if (underFire) it.observe(TacticalFacts.FIRE_SECTOR, FireSector.of(60.0, 0.0)) else it }

    private fun first(input: FactSnapshot) = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(intent), input).advance(60) as PlanningResult.Ready).plan.tasks.first().spec.action

    @Test fun `an operator with a target launches ahead of his duty`() {
        assertEquals(DroneStrike.ACTION, first(facts(Situation(sources = setOf(StrikeSource.SPOTTED)), base(underFire = false))).key)
    }

    @Test fun `with nothing to strike, or the drone kept in its case, he carries out his duty`() {
        assertEquals(TacticalDomain.POSITION, first(facts(Situation(), base(underFire = false))).key)
        assertEquals(TacticalDomain.POSITION, first(facts(Situation(hold = DroneHold.COOLDOWN, sources = setOf(StrikeSource.FOCUS)), base(underFire = false))).key)
    }

    @Test fun `under fire he takes cover before a launch, but keeps flying a drone that is up`() {
        assertEquals(TacticalDomain.COVER_MOVE, first(facts(Situation(sources = setOf(StrikeSource.SPOTTED)), base(underFire = true))).key)
        assertEquals(DroneStrike.ACTION, first(facts(Situation(airborne = true, flying = true), base(underFire = true))).key)
    }

    @Test fun `a man who is no operator plans as before`() {
        assertEquals(TacticalDomain.POSITION, first(base(underFire = false)).key)
    }

    @Test fun `a squadless operator strikes, engages in a fight, else stands by`() {
        fun alone(input: FactSnapshot) = (HtnPlanningSession(TacticalDomain.npc, owner, DroneStrike.ALONE.call(StrikeCall(actor)), input).advance(60) as PlanningResult.Ready).plan
        assertEquals(DroneStrike.ACTION, alone(facts(Situation(sources = setOf(StrikeSource.REPORTED)), FactSnapshot(actor, 1).observe(TacticalFacts.IN_CONTACT, false))).tasks.single().spec.action.key)
        assertTrue(alone(facts(Situation(), FactSnapshot(actor, 1).observe(TacticalFacts.IN_CONTACT, false))).tasks.isEmpty())
    }
}

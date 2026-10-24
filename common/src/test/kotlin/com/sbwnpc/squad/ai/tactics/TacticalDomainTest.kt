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

    @Test fun `exhausted local alternatives hold where he stands until the evidence expires`() {
        val failure = LocalPositionFailure(intent.anchor, 1, 5, mapOf("unreachable_route" to 5))
        val input = facts(1).observe(TacticalFacts.POST_FAILURE, failure, expiresAt = 201)
        val held = (plan(input) as PlanningResult.Ready).plan.tasks.single().spec.action.parameters as PositionIntent
        assertTrue(held.hold)
        assertFalse(held.requireCover)
        assertEquals(intent.anchor, held.anchor)
        val again = (plan(input.at(201)) as PlanningResult.Ready).plan.tasks.single().spec.action.parameters as PositionIntent
        assertEquals(intent, again)
    }

    @Test fun `no post method but the hold applies once every search failed, and the hold applies before that`() {
        val methods = { failure: LocalPositionFailure? ->
            val input = failure?.let { facts(1).observe(TacticalFacts.POST_FAILURE, it, expiresAt = 201) } ?: facts(1)
            val events = ArrayList<PlanningEvent>()
            HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(intent), input, event = events::add).advance(20)
            events.filter { it.node == "root" && it.verdict == Verdict.Allowed }.map { it.method }
        }
        assertEquals(listOf("preferred_post"), methods(null))
        assertEquals(listOf("neighbor_post"), methods(LocalPositionFailure(intent.anchor, 1, 4, mapOf("unreachable_route" to 5))))
        assertEquals(listOf("closer_post"), methods(LocalPositionFailure(intent.anchor, 1, 5, mapOf("blocked_shot" to 93))))
        assertEquals(listOf("hold_as_ordered"), methods(LocalPositionFailure(intent.anchor, 1, 5, mapOf("unreachable_route" to 5))))
    }

    @Test fun `a blind neighbourhood moves the search closer to the enemy`() {
        val blind = LocalPositionFailure(intent.anchor, 1, 5, mapOf("blocked_shot" to 93))
        val selected = (plan(facts(1).observe(TacticalFacts.POST_FAILURE, blind, expiresAt = 201)) as PlanningResult.Ready).plan.tasks.single().spec.action.parameters as PositionIntent
        assertEquals(Point3(62.0, 1.0, 0.0), selected.anchor)
        val next = blind.copy(attempts = 6)
        val second = (plan(facts(1).observe(TacticalFacts.POST_FAILURE, next, expiresAt = 201)) as PlanningResult.Ready).plan.tasks.single().spec.action.parameters as PositionIntent
        assertEquals(Point3(74.0, 1.0, 0.0), second.anchor)
    }

    @Test fun `a mover advancing by legs rushes to the next post and fires from it, leg after leg`() {
        val mover = intent.copy(legs = true)
        fun plan(done: Int?) = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(mover), (done?.let { facts(1).observe(TacticalFacts.LEGS_DONE, it) } ?: facts(1))).advance(20) as PlanningResult.Ready).plan
        val first = plan(0)
        assertEquals(listOf("leg:0", "fire:0"), first.tasks.map { it.spec.identity })
        assertEquals(setOf(first.tasks[0].node), first.tasks[1].after)
        assertEquals(mover, first.tasks[0].spec.action.parameters)
        assertEquals(listOf("leg:2", "fire:2"), plan(2).tasks.map { it.spec.identity })
        assertFalse(first.inputUnchanged(facts(1).observe(TacticalFacts.LEGS_DONE, 1)))
        // An assault, a man without a contact and a man firing keep the single post search.
        for (other in listOf(mover.copy(assault = true), mover.copy(focus = null), mover.copy(duty = CombatDuty.FIRE))) {
            val single = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(other), facts(1)).advance(20) as PlanningResult.Ready).plan
            assertEquals(listOf("position"), single.tasks.map { it.spec.identity })
        }
    }

    @Test fun `a failed leg search falls back to the neighbouring posts without legs`() {
        val mover = intent.copy(legs = true)
        val failure = LocalPositionFailure(mover.anchor, 1, 1, mapOf("unreachable_route" to 5))
        val plan = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(mover), facts(1).observe(TacticalFacts.POST_FAILURE, failure, expiresAt = 201)).advance(20) as PlanningResult.Ready).plan
        val selected = plan.tasks.single().spec.action.parameters as PositionIntent
        assertFalse(selected.legs)
        assertNotEquals(mover.anchor, selected.anchor)
    }

    @Test fun `closer anchors keep the role standoff and stop when used up`() {
        val anchors = TacticalDomain.closerAnchors(intent.copy(standoff = 40.0))
        assertTrue(anchors.isNotEmpty())
        anchors.forEach { assertTrue(kotlin.math.hypot(it.x - 100.0, it.z) >= 40.0) }
        assertTrue(TacticalDomain.closerAnchors(intent.copy(duty = CombatDuty.REAR_GUARD)).isEmpty())
        val used = LocalPositionFailure(intent.anchor, 1, 5 + TacticalDomain.closerAnchors(intent).size, mapOf("blocked_shot" to 93))
        assertTrue(((plan(facts(1).observe(TacticalFacts.POST_FAILURE, used, expiresAt = 201)) as PlanningResult.Ready).plan.tasks.single().spec.action.parameters as PositionIntent).hold)
    }

    @Test fun `foreign actor intent cannot reserve another NPC motion`() {
        val foreign = intent.copy(actor = AgentId(AgentLevel.NPC, UUID(5, 5)))
        val result = HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(foreign), facts(0)).advance(20) as PlanningResult.Failed
        assertTrue("foreign_actor_intent" in result.reasons)
    }

    private val squadId = AgentId(AgentLevel.SQUAD, UUID(0, 9))
    private val blocked = CombatSituation(true, true, false, false, 0.0, 0.0, false)
    private val beaten = CombatSituation(true, true, false, true, 0.5, 0.5, false)

    private fun squadFacts(situation: CombatSituation, held: CombatStrategy? = null, locked: Boolean = false): FactSnapshot {
        var facts = FactSnapshot(squadId, 0).observe(TacticalFacts.SITUATION, situation)
        if (held != null) facts = facts.observe(TacticalFacts.MANNER, held)
        if (locked) facts = facts.observe(TacticalFacts.MANNER_LOCK, true)
        return facts
    }

    private fun squadPlan(facts: FactSnapshot, vararg members: PositionIntent = arrayOf(intent)): HtnPlan {
        val mission = SquadMission(squadId, listOf(TeamMission(AgentId(AgentLevel.TEAM, UUID(0, 8)), members.toList())))
        return (HtnPlanningSession(TacticalDomain.squad, PlanOwner(squadId, UUID(1, 1)), TacticalDomain.SQUAD.call(mission), facts).advance(20) as PlanningResult.Ready).plan
    }

    private fun strategyOf(plan: HtnPlan) = SquadManners.of(plan.tasks.single().node.split('/')[1])

    private fun shapedMember(plan: HtnPlan) = ((plan.tasks.single().spec.action.parameters as com.sbwnpc.squad.ai.coordination.DelegationTask).task.parameters as TeamMission).members.single()

    @Test fun `utility rejects advance without capability and chooses valid height strategy`() {
        assertEquals(CombatStrategy.HOLD, strategyOf(squadPlan(squadFacts(blocked))))
        assertTrue(SquadManners.eligible(CombatStrategy.ADVANCE).evaluate(squadFacts(blocked)) is Verdict.Rejected)
        assertEquals(CombatStrategy.GAIN_HEIGHT, strategyOf(squadPlan(squadFacts(CombatSituation(true, true, true, false, 1.0, 0.0, true)))))
    }

    @Test fun `a squad with a barracks does not withdraw on its losses`() {
        assertEquals(CombatStrategy.WITHDRAW, strategyOf(squadPlan(squadFacts(beaten))))
        assertTrue(SquadManners.eligible(CombatStrategy.WITHDRAW).evaluate(squadFacts(beaten.copy(reinforced = true))) is Verdict.Rejected)
    }

    @Test fun `a squad keeps its strategy while locked or nearly as good and drops it once ruled out`() {
        // Out of contact ADVANCE scores 0.75 here and HOLD 0.4, or 0.52 when held.
        val calm = CombatSituation(false, true, false, false, 1.0, 0.0, false)
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(calm))))
        assertEquals(CombatStrategy.HOLD, strategyOf(squadPlan(squadFacts(calm, CombatStrategy.HOLD, locked = true))))
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(calm, CombatStrategy.HOLD))))
        // WITHDRAW (0.95) beats GAIN_HEIGHT (0.93), but not by the advantage the strategy held has.
        val close = CombatSituation(true, true, true, true, 0.5, 0.5, false)
        assertEquals(CombatStrategy.WITHDRAW, strategyOf(squadPlan(squadFacts(close))))
        assertEquals(CombatStrategy.GAIN_HEIGHT, strategyOf(squadPlan(squadFacts(close, CombatStrategy.GAIN_HEIGHT))))
        assertEquals(CombatStrategy.HOLD, strategyOf(squadPlan(squadFacts(blocked, CombatStrategy.ADVANCE, locked = true))))
    }

    @Test fun `an attack under fire bounds by halves once enough of it is ready, and never without fire or under an enemy above`() {
        val underFire = CombatSituation(true, true, false, true, 0.75, 0.0, true, movers = 4)
        assertEquals(CombatStrategy.BOUND_OVERWATCH, strategyOf(squadPlan(squadFacts(underFire, CombatStrategy.ADVANCE))))
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(underFire.copy(readyFraction = 0.25), CombatStrategy.ADVANCE))))
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(underFire.copy(incoming = false, underFire = false), CombatStrategy.BOUND_OVERWATCH))))
        // A few seconds without a hit keep it bounding while it was hit within the last 10 s.
        assertEquals(CombatStrategy.BOUND_OVERWATCH, strategyOf(squadPlan(squadFacts(underFire.copy(incoming = false), CombatStrategy.BOUND_OVERWATCH))))
        assertTrue(SquadManners.eligible(CombatStrategy.BOUND_OVERWATCH).evaluate(squadFacts(underFire.copy(scattered = true))) is Verdict.Rejected)
        assertEquals(CombatStrategy.GAIN_HEIGHT, strategyOf(squadPlan(squadFacts(underFire.copy(higherEnemy = true), CombatStrategy.BOUND_OVERWATCH))))
        assertTrue(SquadManners.eligible(CombatStrategy.BOUND_OVERWATCH).evaluate(squadFacts(underFire.copy(movers = 3))) is Verdict.Rejected)
    }

    @Test fun `in a bounding turn one half rushes when covered and the other fires from its post`() {
        val runner = intent.copy(legs = true)
        val gunner = intent.copy(actor = AgentId(AgentLevel.NPC, UUID(0, 2)), legs = true)
        val sentry = intent.copy(actor = AgentId(AgentLevel.NPC, UUID(0, 3)), duty = CombatDuty.OBSERVE)
        val post = Point3(3.0, 1.0, 4.0)
        val turn = BoundTurn(0, setOf(runner.actor.id), mapOf(gunner.actor.id to post, sentry.actor.id to post))
        val facts = squadFacts(CombatSituation(true, true, false, true, 1.0, 0.0, true, movers = 4), CombatStrategy.BOUND_OVERWATCH).observe(TacticalFacts.BOUND_TURN, turn)
        val shaped = ((squadPlan(facts, runner, gunner, sentry).tasks.single().spec.action.parameters as com.sbwnpc.squad.ai.coordination.DelegationTask).task.parameters as TeamMission).members
        assertEquals(runner.copy(requireCover = false, coveredBy = turn.posts.keys), shaped[0])
        assertEquals(CombatDuty.FIRE, shaped[1].duty)
        assertEquals(post, shaped[1].anchor)
        assertFalse(shaped[1].legs)
        assertEquals(sentry, shaped[2])
    }

    @Test fun `a rush awaiting support is planned at once and starts only once covered`() {
        val waiting = intent.copy(legs = true, requireCover = false, coveredBy = setOf(UUID(0, 2)))
        val leg = (HtnPlanningSession(TacticalDomain.npc, owner, TacticalDomain.NPC.call(waiting), facts(0)).advance(20) as PlanningResult.Ready).plan.tasks.first().spec
        assertEquals(Verdict.Unknown(setOf(TacticalFacts.SUPPORT_FIRING.name)), leg.contract.start.evaluate(facts(0)))
        assertTrue(leg.contract.start.evaluate(facts(0).observe(TacticalFacts.SUPPORT_FIRING, false)) is Verdict.Rejected)
        assertEquals(Verdict.Allowed, leg.contract.start.evaluate(facts(0).observe(TacticalFacts.SUPPORT_FIRING, true)))
        assertEquals(Verdict.Allowed, leg.contract.maintain.evaluate(facts(0).observe(TacticalFacts.SUPPORT_FIRING, false)))
    }

    @Test fun `a squad that can flank does unless hurt and unready, and comes on frontally when the flank is closed`() {
        fun approachOf(plan: HtnPlan) = SquadApproaches.of(plan.tasks.single().node.split('/')[3])
        val fresh = CombatSituation(false, true, false, false, 0.0, 0.0, false)
        assertEquals(SquadApproach.FLANK, approachOf(squadPlan(squadFacts(fresh).observe(TacticalFacts.FLANK_OPEN, true))))
        assertEquals(SquadApproach.FRONTAL, approachOf(squadPlan(squadFacts(fresh).observe(TacticalFacts.FLANK_OPEN, false))))
        val hurt = fresh.copy(lossFraction = 0.4)
        assertEquals(SquadApproach.FRONTAL, approachOf(squadPlan(squadFacts(hurt).observe(TacticalFacts.FLANK_OPEN, true))))
        val flanking = squadFacts(fresh.copy(lossFraction = 0.2)).observe(TacticalFacts.FLANK_OPEN, true).observe(TacticalFacts.APPROACH, SquadApproach.FLANK)
        assertEquals(SquadApproach.FLANK, approachOf(squadPlan(flanking)))
    }

    @Test fun `a scattered attack regroups at its rally point until gathered, unless it flanks`() {
        val scattered = CombatSituation(false, true, false, false, 0.0, 0.0, false, scattered = true)
        val rally = Point3(-12.0, 1.0, 0.0)
        val scout = intent.copy(actor = AgentId(AgentLevel.NPC, UUID(0, 2)), duty = CombatDuty.SEARCH)
        val facts = squadFacts(scattered, CombatStrategy.ADVANCE).observe(TacticalFacts.RALLY, rally)
        val plan = squadPlan(facts, intent.copy(legs = true, minimumHeight = 0.0, requireShelter = true), scout)
        assertEquals(CombatStrategy.REGROUP, strategyOf(plan))
        val members = ((plan.tasks.single().spec.action.parameters as com.sbwnpc.squad.ai.coordination.DelegationTask).task.parameters as TeamMission).members
        assertEquals(CombatDuty.MOVE, members[0].duty)
        assertEquals(rally, members[0].anchor)
        assertFalse(members[0].legs)
        assertNull(members[0].minimumHeight)
        assertFalse(members[0].requireShelter)
        assertEquals(scout, members[1])
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(scattered.copy(flanking = true), CombatStrategy.ADVANCE))))
        // A squad ready to fight to the last man regroups too, over the advance it holds.
        assertEquals(CombatStrategy.REGROUP, strategyOf(squadPlan(squadFacts(scattered.copy(readyFraction = 1.0), CombatStrategy.ADVANCE))))
        val returning = scattered.copy(scattered = false, regrouping = true)
        assertEquals(CombatStrategy.REGROUP, strategyOf(squadPlan(squadFacts(returning, CombatStrategy.REGROUP))))
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(returning.copy(gathered = true), CombatStrategy.REGROUP))))
        // A regroup that has run its time, or ended lately, gives way however scattered the squad is.
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(scattered.copy(regroupSpent = true), CombatStrategy.REGROUP))))
        assertEquals(CombatStrategy.ADVANCE, strategyOf(squadPlan(squadFacts(scattered.copy(regroupSpent = true), CombatStrategy.ADVANCE))))
    }

    @Test fun `a team under fire with far to go rushes in pairs that cover each other`() {
        val teamId = AgentId(AgentLevel.TEAM, UUID(0, 8))
        fun rushed(vararg members: PositionIntent): Pair<String, List<PositionIntent>> {
            val plan = (HtnPlanningSession(TacticalDomain.team, PlanOwner(teamId, UUID(1, 1)), TacticalDomain.TEAM.call(TeamMission(teamId, members.toList())), FactSnapshot(teamId, 0)).advance(20) as PlanningResult.Ready).plan
            return plan.tasks.first().node.split('/')[1] to plan.tasks.map { (it.spec.action.parameters as com.sbwnpc.squad.ai.coordination.DelegationTask).task.parameters as PositionIntent }
        }
        val men = (1L..3L).map { intent.copy(actor = AgentId(AgentLevel.NPC, UUID(0, it)), legs = true) }
        val (method, shaped) = rushed(*men.toTypedArray())
        assertEquals("pairs", method)
        assertEquals(setOf(men[1].actor.id, men[2].actor.id), shaped[0].coveredBy)
        assertTrue(shaped.all { !it.requireCover && it.coveredBy.size == 2 })
        assertEquals("together", rushed(*men.map { it.copy(requireCover = false) }.toTypedArray()).first)
        assertEquals("together", rushed(*men.map { it.copy(anchor = Point3(8.0, 1.0, 0.0)) }.toTypedArray()).first)
        assertEquals("together", rushed(men[0]).first)
    }

    @Test fun `withdrawal changes execution goal away from known threat without requiring cover fire`() {
        val retreat = shapedMember(squadPlan(squadFacts(beaten)))
        assertTrue(retreat.anchor.x < intent.origin.x)
        assertFalse(retreat.requireCover)
        assertFalse(retreat.requireShelter)
        assertEquals(CombatDuty.MOVE, retreat.duty)
        assertEquals(Point3(50.0, 1.0, 0.0), intent.anchor)
    }

    @Test fun `a sentry withdrawing from his height may come down`() {
        val sentry = intent.copy(duty = CombatDuty.OBSERVE, minimumHeight = intent.origin.y - 2, elevationWeight = 3.0)
        val retreat = shapedMember(squadPlan(squadFacts(beaten), sentry))
        assertEquals(CombatDuty.MOVE, retreat.duty)
        assertNull(retreat.minimumHeight)
        assertEquals(0.0, retreat.elevationWeight)
    }

    @Test fun `holding in contact keeps a bound that is the order's own movement`() {
        val bound = intent.copy(duty = CombatDuty.MOVE)
        fun shaped(member: PositionIntent) = shapedMember(squadPlan(squadFacts(blocked), member))
        assertEquals(CombatDuty.FIRE, shaped(bound).duty)
        assertEquals(CombatDuty.MOVE, shaped(bound.copy(ordered = true)).duty)
    }
}

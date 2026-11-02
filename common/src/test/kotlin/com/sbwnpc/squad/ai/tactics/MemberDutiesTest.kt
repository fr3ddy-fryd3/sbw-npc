package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.coordination.TacticalOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class MemberDutiesTest {
    /** The duty chain as it stood before [MemberDuties], kept to prove the rule list chooses the same. */
    // A verbatim copy of the old chain: its complexity is what the rule list replaced.
    @Suppress("CyclomaticComplexMethod")
    private object LegacyDuty {
        fun duty(inputs: DutyInputs, fireReach: Double, approachOffset: Double): DutyChoice =
            with(inputs) {
                val proposal = proposal
                val approach = proposal.duty == CombatDuty.FIRE && position.distanceTo(proposal.anchor) > fireReach
                val duty = when {
                    elsewhere -> CombatDuty.FOLLOW_ORDER

                    // #254: a noise is looked at under DEFEND, PATROL and MOVE too.
                    scout && order in setOf(TacticalOrder.ATTACK, TacticalOrder.MOVE, TacticalOrder.DEFEND, TacticalOrder.PATROL) -> CombatDuty.SEARCH

                    // A sentry fights from his height, in defence and in a fight too. Sent to fire from where he
                    // stood, a gunner stayed below the tower, and a sentry left it whenever a reported target
                    // hid (#73). Out of ammunition for his main weapon he is no sentry, and a height is no
                    // post round an objective that walks away from it: gunners following their player held
                    // a roof while he left (#116, in-game 20.10).
                    // A height post is searched round the objective, so he first marches there with the
                    // squad: searched from a tower 44-155 blocks off, no post qualified and he never left
                    // the tower (#166).
                    (order == TacticalOrder.DEFEND || (order == TacticalOrder.PATROL && !hasPatrolRoute)) && heightRole && primaryAmmo && !following &&
                        takesSentryPost -> CombatDuty.OBSERVE

                    // A retreat reaches here only without a place at the rally point (#253): retreatSlot is null in this grid.
                    !inContact || order in setOf(TacticalOrder.RETREAT, TacticalOrder.MOVE, TacticalOrder.BARRAGE) -> CombatDuty.FOLLOW_ORDER

                    // Following in a fight: the squad keeps up with its player by bounds, one half running
                    // to its places by him while the other fires, then the other way round (owner, 21.10).
                    // Left to the rule below, the whole squad stood and fought where the contact found
                    // it and let the player walk away (#116). A player in a vehicle is followed by the ride.
                    following && followRide -> CombatDuty.FOLLOW_ORDER

                    following && followBound && followSlot != null -> CombatDuty.MOVE

                    order != TacticalOrder.ATTACK -> CombatDuty.FIRE

                    // Firing on the way would stop him short of his sector.
                    flanking -> CombatDuty.MOVE

                    // A mover that reached its post, or already fired, keeps firing instead of moving on.
                    // So does one that stands in his slot's area without a post: where no post has a
                    // firing lane, in-game 17.10 a flank's movers ran between search posts 10-15 blocks
                    // apart round their slot for minutes (#58).
                    proposal.duty == CombatDuty.MOVE -> if (reachedMove || wasFiring || distance(position, proposal.anchor) <= OperationTactics.MOVE_ARRIVAL) CombatDuty.FIRE else CombatDuty.MOVE

                    approach -> CombatDuty.MOVE

                    else -> proposal.duty
                }
                val anchor = when {
                    duty == CombatDuty.SEARCH -> requireNotNull(inspect) { "a scout needs a point to inspect" }
                    duty in setOf(CombatDuty.FOLLOW_ORDER, CombatDuty.OBSERVE) -> objective ?: position
                    following && duty == CombatDuty.MOVE -> followSlot ?: position
                    order != TacticalOrder.ATTACK -> position
                    duty == CombatDuty.FIRE && proposal.duty == CombatDuty.MOVE -> position
                    approach -> short(proposal.anchor, position, approachOffset)
                    else -> proposal.anchor
                }
                DutyChoice(duty, anchor)
            }

        private fun short(post: Point3, from: Point3, offset: Double): Point3 {
            val dx = from.x - post.x
            val dz = from.z - post.z
            val length = hypot(dx, dz)
            if (length < 1.0E-4) return post
            return Point3(post.x + dx / length * offset, post.y, post.z + dz / length * offset)
        }

        private fun distance(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)
    }

    // One loop per input of the grid it walks.
    @Suppress("NestedBlockDepth")
    @Test
    fun `the rule list chooses exactly what the old duty chain chose`() {
        val here = Point3(50.0, 64.0, 100.0)
        val proposals = listOf(CombatDuty.MOVE, CombatDuty.FIRE, CombatDuty.REAR_GUARD).flatMap { duty ->
            // Near (within move arrival and fire reach), mid (beyond arrival) and far (beyond fire reach).
            listOf(Point3(54.0, 64.0, 100.0), Point3(70.0, 64.0, 100.0), Point3(120.0, 64.0, 100.0)).map { FrontProposal(duty, it, null) }
        }
        val objectives = listOf(null, Point3(60.0, 64.0, 100.0), Point3(150.0, 64.0, 100.0))
        var checked = 0
        for (order in TacticalOrder.entries) {
            for (proposal in proposals) {
                for (objective in objectives) {
                    for (flags in 0 until (1 shl 14)) {
                        fun flag(bit: Int) = flags shr bit and 1 == 1
                        // A squad following its player fights under DEFEND (SquadOrder.tactical); the old
                        // chain anchored a following mover oddly under orders a follower never has.
                        if (flag(10) && order != TacticalOrder.DEFEND) continue
                        val inputs = DutyInputs(
                            order = order,
                            position = here,
                            proposal = proposal,
                            objective = objective,
                            inspect = Point3(90.0, 64.0, 100.0),
                            inContact = flag(0),
                            elsewhere = flag(1),
                            scout = flag(2),
                            heightRole = flag(3),
                            primaryAmmo = flag(4),
                            hasPatrolRoute = flag(5),
                            reachedMove = flag(6),
                            wasFiring = flag(7),
                            arrival = 22.0,
                            flanking = flag(8),
                            wasObserving = flag(9),
                            following = flag(10),
                            followBound = flag(11),
                            followSlot = if (flag(12)) Point3(52.0, 64.0, 104.0) else null,
                            followRide = flag(13),
                        )
                        assertEquals(LegacyDuty.duty(inputs, 40.0, 10.0), MemberDuties.choose(inputs, 40.0, 10.0).choice, "$inputs")
                        checked++
                    }
                }
            }
        }
        // Half of the flag combinations follow a player, and of those only DEFEND's are checked.
        assertEquals((TacticalOrder.entries.size + 1) * proposals.size * objectives.size * (1 shl 13), checked)
    }

    @Test
    fun `on his sidearm a man in a fight fires from where he is, unless his half bounds after his player`() {
        val here = Point3(50.0, 64.0, 100.0)
        val attack = DutyInputs(
            order = TacticalOrder.ATTACK,
            position = here,
            proposal = FrontProposal(CombatDuty.MOVE, Point3(90.0, 64.0, 100.0), null),
            objective = Point3(120.0, 64.0, 100.0),
            inspect = null,
            inContact = true,
            elsewhere = false,
            scout = false,
            heightRole = false,
            primaryAmmo = false,
            hasPatrolRoute = false,
            reachedMove = false,
            wasFiring = false,
            arrival = 22.0,
            sidearm = true,
        )
        assertEquals(RuledDuty("sidearm_fire", DutyChoice(CombatDuty.FIRE, here)), MemberDuties.choose(attack, 40.0, 10.0))
        assertEquals("mover", MemberDuties.choose(attack.copy(sidearm = false), 40.0, 10.0).rule)
        assertEquals("no_fight", MemberDuties.choose(attack.copy(inContact = false), 40.0, 10.0).rule)
        val slot = Point3(52.0, 64.0, 104.0)
        val bounding = attack.copy(order = TacticalOrder.DEFEND, following = true, followBound = true, followSlot = slot)
        assertEquals(RuledDuty("follow_bound", DutyChoice(CombatDuty.MOVE, slot)), MemberDuties.choose(bounding, 40.0, 10.0))
    }

    @Test
    fun `a retreating man bounds to his rally place in contact, runs there without, and fires once there`() {
        val here = Point3(50.0, 64.0, 100.0)
        val slot = Point3(10.0, 64.0, 100.0)
        val retreat = DutyInputs(
            order = TacticalOrder.RETREAT,
            position = here,
            proposal = FrontProposal(CombatDuty.FIRE, Point3(90.0, 64.0, 100.0), null),
            objective = Point3(8.0, 64.0, 100.0),
            inspect = null,
            inContact = true,
            elsewhere = false,
            scout = false,
            heightRole = true,
            primaryAmmo = true,
            hasPatrolRoute = false,
            reachedMove = false,
            wasFiring = false,
            arrival = 22.0,
            wasObserving = true,
            retreatSlot = slot,
        )
        assertEquals(RuledDuty("retreat_bound", DutyChoice(CombatDuty.MOVE, slot)), MemberDuties.choose(retreat, 40.0, 10.0))
        assertEquals(RuledDuty("retreat_run", DutyChoice(CombatDuty.MOVE, slot)), MemberDuties.choose(retreat.copy(inContact = false), 40.0, 10.0))
        // Six blocks off, height included, he is at his place.
        val there = Point3(14.0, 68.0, 100.0)
        assertEquals(RuledDuty("retreat_settled", DutyChoice(CombatDuty.FIRE, slot)), MemberDuties.choose(retreat.copy(position = there), 40.0, 10.0))
        val off = Point3(14.0, 69.0, 100.0)
        assertEquals("retreat_bound", MemberDuties.choose(retreat.copy(position = off), 40.0, 10.0).rule)
        // Already firing at his place, a post a few blocks farther out keeps him settled.
        assertEquals("retreat_settled", MemberDuties.choose(retreat.copy(position = off, wasFiring = true), 40.0, 10.0).rule)
        assertEquals("retreat_bound", MemberDuties.choose(retreat.copy(position = Point3(23.0, 64.0, 100.0), wasFiring = true), 40.0, 10.0).rule)
        assertEquals("elsewhere", MemberDuties.choose(retreat.copy(elsewhere = true), 40.0, 10.0).rule)
    }

    @Test
    fun `a man holds a fresh fire post at the front before moving again, and switches at once for anything else`() {
        val post = Point3(0.0, 1.0, 0.0)
        // A support post that moved just out of his reach: he would walk to it, then fire, then walk.
        val firing = HeldDuty("front_proposal", DutyChoice(CombatDuty.FIRE, post), since = 100)
        val move = RuledDuty("fire_approach", DutyChoice(CombatDuty.MOVE, Point3(20.0, 1.0, 0.0)))
        val held = MemberDuties.switching(firing, move, 100 + MemberDuties.FIRE_DWELL_TICKS - 1)
        assertEquals(RuledDuty(MemberDuties.FIRE_HELD, firing.choice), held)
        assertEquals(move, MemberDuties.switching(firing, move, 100 + MemberDuties.FIRE_DWELL_TICKS))
        // A retreat, a flank the squad splits off, an order or a first post is never held back.
        val flank = RuledDuty("flank_march", DutyChoice(CombatDuty.MOVE, Point3(0.0, 1.0, 40.0)))
        assertEquals(flank, MemberDuties.switching(firing, flank, 101))
        val retreat = RuledDuty("retreat_run", DutyChoice(CombatDuty.MOVE, Point3(-30.0, 1.0, 0.0)))
        assertEquals(retreat, MemberDuties.switching(firing, retreat, 101))
        assertEquals(move, MemberDuties.switching(null, move, 101))
        // Taking up a post is not delayed.
        val moving = HeldDuty("mover", move.choice, since = 100)
        val settle = RuledDuty("mover_settled", DutyChoice(CombatDuty.FIRE, post))
        assertEquals(settle, MemberDuties.switching(moving, settle, 101))
    }
}

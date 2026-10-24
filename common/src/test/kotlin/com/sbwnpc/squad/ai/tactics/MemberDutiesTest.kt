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

                    scout && order == TacticalOrder.ATTACK -> CombatDuty.SEARCH

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
}

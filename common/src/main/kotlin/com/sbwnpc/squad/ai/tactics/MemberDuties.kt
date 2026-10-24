package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.coordination.TacticalOrder
import kotlin.math.hypot

/** A member's duty chosen by [rule], the id of the [MemberDuties] case that applied. */
data class RuledDuty(val rule: String, val choice: DutyChoice)

/** The post a member's new duty asks for, to compare with the intent he holds; see [MemberDuties.keeps]. */
data class WantedPost(val duty: CombatDuty, val anchor: Point3, val requireCover: Boolean, val requireShelter: Boolean, val legs: Boolean)

/**
 * The cases that decide what one member does and where, in order: the first that applies wins.
 *
 * Each case names its duty and its anchor together, so a case reads as one decision. Later cases
 * may assume the earlier ones did not apply (an ATTACK order past `defending_fire`, for example).
 * The front's proposals ([FrontProposal]) only carry MOVE, FIRE and REAR_GUARD.
 */
object MemberDuties {
    /** How far below where a sentry stands his height post may be. */
    const val HEIGHT_POST_DROP = 2.0

    private class Situation(val inputs: DutyInputs, fireReach: Double, private val approachOffset: Double) {
        val proposal = inputs.proposal
        val approach = proposal.duty == CombatDuty.FIRE && inputs.position.distanceTo(proposal.anchor) > fireReach
        val objectiveOrHere get() = inputs.objective ?: inputs.position
        val approachAnchor get() = short(proposal.anchor, inputs.position, approachOffset)
    }

    private class Rule(val id: String, val choose: (Situation) -> DutyChoice?)

    private fun rule(id: String, applies: (Situation) -> Boolean, duty: (Situation) -> CombatDuty, anchor: (Situation) -> Point3) = Rule(id) { s -> if (applies(s)) DutyChoice(duty(s), anchor(s)) else null }

    private val RULES = listOf(
        rule("elsewhere", { it.inputs.elsewhere || it.inputs.transit }, { CombatDuty.FOLLOW_ORDER }, { it.objectiveOrHere }),
        rule("scout", { it.inputs.scout && it.inputs.order == TacticalOrder.ATTACK }, { CombatDuty.SEARCH }, { requireNotNull(it.inputs.inspect) { "a scout needs a point to inspect" } }),
        // A sentry fights from his height, in defence and in a fight too. Sent to fire from where he
        // stood, a gunner stayed below the tower, and a sentry left it whenever a reported target
        // hid (#73). Out of ammunition for his main weapon he is no sentry, and a height is no
        // post round an objective that walks away from it: gunners following their player held
        // a roof while he left (#116, in-game 20.10).
        // A height post is searched round the objective, so he first marches there with the
        // squad: searched from a tower 44-155 blocks off, no post qualified and he never left
        // the tower (#166).
        rule("sentry_post", { s ->
            with(s.inputs) { (order == TacticalOrder.DEFEND || (order == TacticalOrder.PATROL && !hasPatrolRoute)) && heightRole && primaryAmmo && !following && takesSentryPost }
        }, { CombatDuty.OBSERVE }, { it.objectiveOrHere }),
        rule("no_fight", { !it.inputs.inContact || it.inputs.order in MARCHING_ORDERS }, { CombatDuty.FOLLOW_ORDER }, { it.objectiveOrHere }),
        // Following in a fight: the squad keeps up with its player by bounds, one half running
        // to its places by him while the other fires, then the other way round (owner, 21.10).
        // Left to the rule below, the whole squad stood and fought where the contact found
        // it and let the player walk away (#116). A player in a vehicle is followed by the ride.
        rule("follow_ride", { it.inputs.following && it.inputs.followRide }, { CombatDuty.FOLLOW_ORDER }, { it.objectiveOrHere }),
        rule("follow_bound", { it.inputs.following && it.inputs.followBound && it.inputs.followSlot != null }, { CombatDuty.MOVE }, { it.inputs.followSlot!! }),
        rule("defending_fire", { it.inputs.order != TacticalOrder.ATTACK }, { CombatDuty.FIRE }, { it.inputs.position }),
        // Firing on the way would stop him short of his sector.
        rule("flank_march", { it.inputs.flanking }, { CombatDuty.MOVE }, { if (it.approach) it.approachAnchor else it.proposal.anchor }),
        // A mover that reached its post, or already fired, keeps firing instead of moving on.
        // So does one that stands in his slot's area without a post: where no post has a
        // firing lane, in-game 17.10 a flank's movers ran between search posts 10-15 blocks
        // apart round their slot for minutes (#58).
        rule("mover_settled", { s ->
            with(s.inputs) { proposal.duty == CombatDuty.MOVE && (reachedMove || wasFiring || distance(position, proposal.anchor) <= OperationTactics.MOVE_ARRIVAL) }
        }, { CombatDuty.FIRE }, { it.inputs.position }),
        rule("mover", { it.proposal.duty == CombatDuty.MOVE }, { CombatDuty.MOVE }, { it.proposal.anchor }),
        // A local fire-post query cannot see a post beyond its reach: approach it first.
        rule("fire_approach", { it.approach }, { CombatDuty.MOVE }, { it.approachAnchor }),
        rule("front_proposal", { true }, { it.proposal.duty }, { it.proposal.anchor }),
    )

    private val MARCHING_ORDERS = setOf(TacticalOrder.RETREAT, TacticalOrder.MOVE, TacticalOrder.BARRAGE)

    /**
     * What one member does under his order and where. A fire post farther than [fireReach] is
     * first approached as movement to [approachOffset] short of it.
     */
    fun choose(inputs: DutyInputs, fireReach: Double, approachOffset: Double): RuledDuty {
        val situation = Situation(inputs, fireReach, approachOffset)
        for (rule in RULES) rule.choose(situation)?.let { return RuledDuty(rule.id, it) }
        error("front_proposal always applies")
    }

    /** The rule ids in the order they are tried. */
    val ruleIds: List<String> get() = RULES.map { it.id }

    /**
     * Whether a member keeps his [previous] intent instead of a new one for the [wanted] post.
     *
     * A height post is chosen by height round its anchor, not by where the enemy is or by the
     * squad's strategy, which reshapes a sentry's task only to withdraw. Rebuilt on either, a
     * sentry's post was thrown away and searched again every few seconds (#73). Any other post is
     * kept while the squad's strategy and the contact stay as they were ([settled]) and he is
     * neither on a flank's arc nor bounding after his player, or while the new anchor is within 4
     * blocks of the old one.
     */
    fun keeps(previous: PositionIntent?, wanted: WantedPost, settled: Boolean, moving: Boolean): Boolean {
        previous ?: return false
        val heightPost = wanted.duty.heightPost && previous.anchor == wanted.anchor
        val samePost = heightPost || (settled && (!moving || previous.anchor.distanceTo(wanted.anchor) < SAME_ANCHOR))
        return previous.duty == wanted.duty && previous.requireCover == wanted.requireCover && previous.requireShelter == wanted.requireShelter && previous.legs == wanted.legs && samePost
    }

    private const val SAME_ANCHOR = 4.0

    /** Below this he stands on the post, with no direction to step back along. */
    private const val MIN_LENGTH = 1.0E-4

    private fun short(post: Point3, from: Point3, offset: Double): Point3 {
        val dx = from.x - post.x
        val dz = from.z - post.z
        val length = hypot(dx, dz)
        if (length < MIN_LENGTH) return post
        return Point3(post.x + dx / length * offset, post.y, post.z + dz / length * offset)
    }

    private fun distance(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)
}

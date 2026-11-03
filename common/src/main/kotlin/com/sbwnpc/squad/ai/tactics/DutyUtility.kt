package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.ExcludedDecision
import com.sbwnpc.squad.ai.planning.RankedDecision
import com.sbwnpc.squad.ai.planning.UtilityScore
import com.sbwnpc.squad.ai.planning.Verdict

/** The front options weighed for one member: the [ranked] allowed ones, best first, and the [excluded] ones with why. */
data class DutyEvaluation(val ranked: List<RankedDecision<DutyChoice>>, val excluded: List<ExcludedDecision>) {
    /** As the decision trace shows it: `move_to_front 0.55 (fire_here 0.50; x front_proposal:proposal_moves)`. */
    fun describe(): String {
        val best = ranked.first()
        val others = ranked.drop(1).map { "${it.id} ${"%.2f".format(it.score.value)}" } +
            excluded.map { "x ${it.id}:${(it.verdict as? Verdict.Rejected)?.reason ?: it.verdict}" }
        return "${best.id} ${"%.2f".format(best.score.value)}" + if (others.isEmpty()) "" else " (${others.joinToString("; ")})"
    }

    /** Whether the best two allowed options are within [margin] of each other, so a small change could flip him. */
    fun close(margin: Double): Boolean = ranked.size >= 2 && ranked[0].score.value - ranked[1].score.value < margin
}

/**
 * The duties a member in contact under an ATTACK may take at the front, weighed by Utility (#262).
 *
 * [MemberDuties]' hard rules come first: orders, a retreat, a scout, a sentry, a follow, a sidearm
 * and a flank decide by themselves. What is left is weighed here: an option that cannot apply is
 * excluded with its reason before any scoring, the way the squad's strategies are
 * ([SquadManners]), and the rest are ranked. The trace then shows every option, not only the rule
 * that fired.
 */
object DutyUtility {
    /**
     * What the options read: the member's [inputs], whether his fire post is beyond reach
     * ([approach]) with the place short of it to make for, and the duty he [held] until now.
     */
    class FrontInputs(val inputs: DutyInputs, val approach: Boolean, val approachAnchor: Point3, val held: HeldDuty? = null) {
        val proposal get() = inputs.proposal

        /** Nothing to fire: no ammunition for his main weapon and no sidearm in hand (that one is `sidearm_fire`'s). */
        val unarmed get() = !inputs.primaryAmmo && !inputs.sidearm

        /**
         * A mover that reached his post, or already fired, or stands in his slot's area keeps firing
         * instead of moving on: where no post has a firing lane, in-game 17.10 a flank's movers ran
         * between search posts 10-15 blocks apart round their slot for minutes (#58).
         */
        val settled
            get() = proposal.duty == CombatDuty.MOVE &&
                (inputs.reachedMove || inputs.wasFiring || MemberDuties.distance(inputs.position, proposal.anchor) <= OperationTactics.MOVE_ARRIVAL)
    }

    private class Option(val id: String, val eligible: (FrontInputs) -> Verdict, val score: (FrontInputs) -> UtilityScore, val choice: (FrontInputs) -> DutyChoice)

    private fun allowedIf(test: Boolean, reason: String) = if (test) Verdict.Allowed else Verdict.Rejected(reason)

    /** Fire from where he stands. */
    const val FIRE_HERE = "fire_here"

    /** Move to the front's post, or short of a fire post beyond reach: a local fire-post query cannot see past its reach. */
    const val MOVE_TO_FRONT = "move_to_front"

    /** The front's own duty at its post: a fire post in reach, or a rear guard's. */
    const val FRONT_PROPOSAL = "front_proposal"

    /**
     * Fire from a post that hides him, near where he stands, instead of his place at the front
     * ([CombatDuty.TAKE_COVER]): a
     * wounded man, or one with nothing left to fire, stood in the front row with the rest until
     * he fell (owner, 3.11). With no ammunition at all the mandatory ammunition rule then takes
     * him back; until it does, he waits in cover.
     */
    const val TAKE_COVER = "take_cover"

    /**
     * A sniper or machine gunner takes the rise by the front ([DutyInputs.height]) instead of his
     * place in the line (owner, 3.11): from above he covers both halves of a bounding squad, and
     * where a rise is, his post on it is searched as any height post's. On flat ground he keeps
     * his place, and the squad's halves keep a heavy gun each ([BoundHalves]).
     */
    const val OBSERVE_HEIGHT = "observe_height"

    /** Below this share of his health a man in contact takes cover. */
    const val WOUNDED = 0.40

    /**
     * Above this share he goes back to the front. Between the two he keeps what he does: healing
     * across one threshold, he flipped between the front and cover with every point a medic gave him.
     */
    const val RECOVERED = 0.55

    // Listed in the order a tie is broken in: the front's own shape first, then moving up to it,
    // then firing where he is, then a height, then taking cover.
    private val OPTIONS = listOf(
        Option(FRONT_PROPOSAL, { f ->
            allowedIf(f.proposal.duty != CombatDuty.MOVE && !f.approach, if (f.approach) "post_beyond_reach" else "proposal_moves")
        }, { UtilityScore(FRONT_PROPOSAL_SCORE, reason = "front_shape") }, { f -> DutyChoice(f.proposal.duty, f.proposal.anchor) }),
        Option(MOVE_TO_FRONT, { f ->
            when {
                f.unarmed -> Verdict.Rejected("no_ammunition")
                else -> allowedIf((f.proposal.duty == CombatDuty.MOVE && !f.settled) || f.approach, if (f.settled) "settled" else "post_in_reach")
            }
        }, { UtilityScore(MOVE_SCORE, reason = "close_up") }, { f -> DutyChoice(CombatDuty.MOVE, if (f.approach) f.approachAnchor else f.proposal.anchor) }),
        Option(FIRE_HERE, { f ->
            allowedIf(f.settled, "not_settled")
        }, { UtilityScore(FIRE_HERE_SCORE, reason = "settled") }, { f -> DutyChoice(CombatDuty.FIRE, f.inputs.position) }),
        Option(OBSERVE_HEIGHT, { f ->
            when {
                !f.inputs.heightRole -> Verdict.Rejected("not_a_height_role")
                f.unarmed -> Verdict.Rejected("no_ammunition")
                else -> allowedIf(f.inputs.height != null, "no_rise")
            }
        }, { UtilityScore(HEIGHT_SCORE, reason = "rise_by_front") }, { f -> DutyChoice(CombatDuty.OBSERVE, f.inputs.height!!) }),
        Option(TAKE_COVER, { f ->
            allowedIf(f.unarmed || f.inputs.health < if (f.held?.rule == TAKE_COVER) RECOVERED else WOUNDED, "fit")
        }, { f ->
            // It outscores every other option, held or not: a wounded man is worth more to the squad alive.
            if (f.unarmed) UtilityScore(1.0, reason = "no_ammunition") else UtilityScore(minOf(1.0, COVER_BASE + COVER_WOUND * (1.0 - f.inputs.health / RECOVERED)), reason = "wounded")
        }, { f -> DutyChoice(CombatDuty.TAKE_COVER, f.inputs.position) }),
    )

    /** The option ids, in the order a tie is broken in. */
    val optionIds: List<String> = OPTIONS.map { it.id }

    /**
     * How much more the option a man holds is worth, so a small change in the scores does not
     * switch him. A member's own scale: the squad's strategies have theirs ([SquadManners.ADVANTAGE]).
     */
    const val HELD_ADVANTAGE = 0.1

    /**
     * Options this near each other are logged as close (`duty_close`): within [HELD_ADVANTAGE] a
     * settled man's held fire post and his move on were always "close", and the log said nothing.
     */
    const val CLOSE_MARGIN = 0.05

    /**
     * The cost of switching back (#262, owner 2.11): a man who settled on a fire post at the front
     * holds it for at least [FIRE_DWELL_TICKS] before an option moves him again. On the edge of a
     * condition (just beyond a fire post's reach, just short of a mover's arrival) he flipped
     * between firing and moving every update, and did neither. Taking up a post is never delayed,
     * and a hard rule (an order, a retreat, a scout, a follow) switches him at once, since it never
     * reaches here.
     */
    const val FIRE_DWELL_TICKS = 60L

    /** [front]'s best option, the one he held given [HELD_ADVANTAGE] and kept as it was through its fire dwell. */
    fun choose(front: FrontInputs, now: Long): DutyDecision {
        val held = front.held
        val ranked = ArrayList<RankedDecision<DutyChoice>>(OPTIONS.size)
        val excluded = ArrayList<ExcludedDecision>(OPTIONS.size)
        for (option in OPTIONS) {
            when (val verdict = option.eligible(front)) {
                Verdict.Allowed -> {
                    val base = option.score(front)
                    val score = if (held?.rule == option.id) base.copy(value = minOf(1.0, base.value + HELD_ADVANTAGE)) else base
                    ranked += RankedDecision(option.id, option.choice(front), score)
                }

                else -> excluded += ExcludedDecision(option.id, verdict)
            }
        }
        check(ranked.isNotEmpty()) { "a front proposal always leaves an option: $excluded" }
        // Stable: on equal scores the earlier option in OPTIONS wins.
        ranked.sortWith(compareByDescending { it.score.value })
        val evaluation = DutyEvaluation(ranked, excluded)
        val best = ranked.first()
        val dwelling = held?.takeIf { it.rule in optionIds && it.choice.duty == CombatDuty.FIRE && best.value.duty == CombatDuty.MOVE && now - it.since < FIRE_DWELL_TICKS }
        return DutyDecision(dwelling?.let { RuledDuty(it.rule, it.choice) } ?: RuledDuty(best.id, best.value), evaluation)
    }

    private const val FRONT_PROPOSAL_SCORE = 0.6
    private const val MOVE_SCORE = 0.55
    private const val FIRE_HERE_SCORE = 0.5

    /** Above the front's shape held ([FRONT_PROPOSAL_SCORE] + [HELD_ADVANTAGE]): a rise is worth leaving the line for. */
    private const val HEIGHT_SCORE = 0.75

    /** A wounded man outscores every other option held, a height's ([HEIGHT_SCORE] + [HELD_ADVANTAGE]) included. */
    private const val COVER_BASE = 0.9
    private const val COVER_WOUND = 0.1
}

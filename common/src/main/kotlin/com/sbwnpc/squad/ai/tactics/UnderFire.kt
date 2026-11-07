package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.FactView
import com.sbwnpc.squad.ai.planning.Verdict

/**
 * The conditions of the NPC domain's answer to fire (#251): which of its methods applies to a man
 * under fire or in the hole he dug, so that exactly one family of methods applies to any man.
 *
 * The planner sorts allowed methods by score and then by id, and NPC methods score alike, so the
 * order of methods cannot rank them: every condition here says outright when its method stands aside.
 */
object UnderFire {
    /** Blocks of foxhole a man digs at most; see `CoverMechanics.MAX_DIGS` for why it is capped. */
    const val MAX_DIGS = 2

    /**
     * An operation's assault keeps running onto its ring, and a man moving for his order itself keeps
     * moving (#116); every other man answers fire first.
     */
    private val PositionIntent.answers get() = !assault && !ordered

    private val FactView.firing get() = value(TacticalFacts.UNDER_FIRE) == true

    private val FactView.digs get() = value(TacticalFacts.DIGS) ?: 0

    /** Why a duty method stands aside for [intent], or null when it may apply. */
    fun duty(intent: PositionIntent, facts: FactView): Verdict? =
        when {
            facts.digs > 0 -> Verdict.Rejected("in_hole")
            facts.firing && intent.answers -> Verdict.Rejected("under_fire")
            else -> null
        }

    /** The man is under fire, not in a hole, and answers fire. */
    fun eligible(intent: PositionIntent) =
        Condition { facts ->
            when {
                facts.digs > 0 -> Verdict.Rejected("in_hole")
                !facts.firing -> Verdict.Rejected("not_under_fire")
                !intent.answers -> Verdict.Rejected("keeps_moving")
                else -> Verdict.Allowed
            }
        }

    /** He is in the hole he dug. */
    fun inHole() = Condition { facts -> if (facts.digs > 0) Verdict.Allowed else Verdict.Rejected("no_hole") }

    private val FactView.failedHere get() = value(TacticalFacts.COVER_FAILURE)?.let { it.sector == value(TacticalFacts.FIRE_SECTOR) } == true

    /** No search for cover from this fire has failed yet. */
    fun coverLeft() = Condition { facts -> if (facts.failedHere) Verdict.Rejected("cover_exhausted") else Verdict.Allowed }

    /** Cover from this fire failed, and digging in is possible exactly when [dig]. */
    fun noCover(dig: Boolean) =
        Condition { facts ->
            when {
                !facts.failedHere -> Verdict.Rejected("cover_left")
                (facts.value(TacticalFacts.DIG_POSSIBLE) == true) != dig -> Verdict.Rejected(if (dig) "cannot_dig" else "can_dig")
                else -> Verdict.Allowed
            }
        }

    private val FactView.leaves get() = value(TacticalFacts.HEALED) == true || (!firing && value(TacticalFacts.ENGAGED) != true)

    private val FactView.deeper get() = !leaves && value(TacticalFacts.HIT_IN_HOLE) == true && digs < MAX_DIGS && value(TacticalFacts.DIG_POSSIBLE) == true

    /** Healed, or with nothing shooting at him and nothing to shoot at: he climbs out. */
    fun leaveHole() = Condition { facts -> if (facts.leaves) Verdict.Allowed else Verdict.Rejected("still_fighting") }

    /** Hit again while dug in, below the cap, on ground that still allows it: one block deeper. */
    fun digDeeper() = Condition { facts -> if (facts.deeper) Verdict.Allowed else Verdict.Rejected("no_deeper") }

    /** A hold in the hole is over once he is hit again or would leave: the plan looks again. */
    fun holdOver() = Condition { facts -> if (facts.value(TacticalFacts.HIT_IN_HOLE) == true || facts.leaves) Verdict.Allowed else Verdict.Rejected("holding") }

    /** Otherwise he holds the hole and fights from it. */
    fun holdHole() = Condition { facts -> if (!facts.leaves && !facts.deeper) Verdict.Allowed else Verdict.Rejected("leaving_or_digging") }
}

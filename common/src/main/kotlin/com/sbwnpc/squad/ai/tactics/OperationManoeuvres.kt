package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.Consideration
import com.sbwnpc.squad.ai.planning.ResponseCurve
import com.sbwnpc.squad.ai.planning.UtilityScore
import com.sbwnpc.squad.ai.planning.Verdict
import kotlin.math.PI
import kotlin.math.abs

/** How an attack of two groups (two squads, or a lone squad's fire and flank teams) comes at its objective (#173). */
enum class OperationManoeuvre {
    /** Today's layout: the second group takes the flank sector nearer to it. */
    ENVELOP,

    /** The second group goes round to the left flank sector (+60° from head-on). */
    FLANK_LEFT,

    /** The second group goes round to the right flank sector (−60°). */
    FLANK_RIGHT,

    /** The second group, standing high off to a side, attacks the flank from where it stands. */
    FROM_HEIGHT,
}

/**
 * What the operation's manoeuvre is scored on, measured once at first contact for an attack of
 * exactly two groups (more keep [OperationManoeuvre.ENVELOP]: one side for three groups stacked
 * them in front of each other, #58).
 *
 * @property left the walk to the left flank sector and its ground there, null when unknown.
 * @property right the same for the right.
 * @property height the second group's stand when it is high off to a side, null otherwise.
 */
data class ManoeuvreAssessment(val left: Side? = null, val right: Side? = null, val height: HeightStand? = null) {
    /**
     * @property route blocks walked round the point to the sector, along the arc the flank walks.
     * @property rise ground height at the sector's line over the objective; null when not loaded.
     */
    data class Side(val route: Double, val rise: Double?)

    /**
     * @property offset the group's bearing from head-on, radians, signed as the sectors are.
     * @property rise its height over the objective, and [overAllies] over the pinning group.
     * @property defence blocks to the nearest defender it knows of.
     */
    data class HeightStand(val offset: Double, val rise: Double, val overAllies: Double, val defence: Double)
}

/**
 * Scores the operation's manoeuvres (#173, utility over the allowed ones, #187 considerations).
 *
 * [OperationManoeuvre.ENVELOP] is always allowed and wins ties and missing data: it is today's
 * layout. A flank to one side wins when the walk there is short and its ground high; a long way
 * round scores low (owner, 08.11). Attacking from a height needs a group already standing high off
 * to a side and near the defence; it walks nowhere to get there.
 */
object OperationManoeuvres {
    fun eligible(option: OperationManoeuvre, input: ManoeuvreAssessment?): Condition =
        Condition {
            when {
                option == OperationManoeuvre.ENVELOP -> Verdict.Allowed
                input == null -> Verdict.Rejected("not_two_groups")
                option == OperationManoeuvre.FROM_HEIGHT -> if (input.height != null) Verdict.Allowed else Verdict.Rejected("no_height")
                side(option, input)?.rise == null -> Verdict.Rejected("side_unknown")
                else -> Verdict.Allowed
            }
        }

    fun score(option: OperationManoeuvre, input: ManoeuvreAssessment?): UtilityScore =
        when (option) {
            OperationManoeuvre.ENVELOP -> UtilityScore.product(ENVELOP, emptyList(), "envelop")

            OperationManoeuvre.FROM_HEIGHT -> {
                val stand = input?.height
                UtilityScore.product(
                    HEIGHT_BASE,
                    listOf(
                        Consideration("rise", stand?.rise, ResponseCurve.floored(HEIGHT_FLOOR, MIN_RISE, HIGH)),
                        Consideration("defence", stand?.defence, ResponseCurve.floored(NEAR_FLOOR, NEAR_LIMIT, CLOSE)),
                    ),
                    "from_height",
                )
            }

            else -> {
                val side = input?.let { side(option, it) }
                UtilityScore.product(
                    FLANK_BASE,
                    listOf(
                        Consideration("route", side?.route, ResponseCurve.floored(LONG_FLOOR, LONG_ROUTE, SHORT_ROUTE)),
                        Consideration("rise", side?.rise, ResponseCurve.floored(FLAT_FLOOR, 0.0, HIGH)),
                    ),
                    option.name.lowercase(),
                )
            }
        }

    /** The offset from head-on the second group takes under [option], or null for the nearer flank as today. */
    fun offset(option: OperationManoeuvre, input: ManoeuvreAssessment?): Double? =
        when (option) {
            OperationManoeuvre.ENVELOP -> null
            OperationManoeuvre.FLANK_LEFT -> ApproachSectors.FLANK_ANGLE
            OperationManoeuvre.FLANK_RIGHT -> -ApproachSectors.FLANK_ANGLE
            OperationManoeuvre.FROM_HEIGHT -> input?.height?.offset
        }

    /** The method id of [option] in the operation's plan. */
    fun methodId(option: OperationManoeuvre) = option.name.lowercase()

    /** The option of a plan node's method id, or null. */
    fun of(id: String) = OperationManoeuvre.entries.firstOrNull { methodId(it) == id }

    /** Whether a group at [offset] from head-on stands off to a side enough to attack the flank from there, and not behind. */
    fun flankStand(offset: Double) = abs(offset) in MIN_STAND..MAX_STAND

    private fun side(option: OperationManoeuvre, input: ManoeuvreAssessment) = if (option == OperationManoeuvre.FLANK_LEFT) input.left else input.right

    // A flank on short, high ground (0.7 at best) beats the envelop's 0.6; flat or a long way
    // round it falls below, and ties go to the envelop, first by name.
    private const val ENVELOP = 0.6
    private const val FLANK_BASE = 0.7
    private const val SHORT_ROUTE = 40.0
    private const val LONG_ROUTE = 160.0
    private const val LONG_FLOOR = 0.3
    private const val FLAT_FLOOR = 0.7

    /** Height over the objective that counts as high ground. */
    private const val HIGH = 8.0

    private const val HEIGHT_BASE = 0.75
    private const val HEIGHT_FLOOR = 0.6
    private const val NEAR_FLOOR = 0.5
    private const val NEAR_LIMIT = 64.0
    private const val CLOSE = 24.0

    /** A group must stand this high over the objective and its allies to attack from its height. */
    const val MIN_RISE = 4.0

    /** And within this of the defence: farther off, its height covers nothing. */
    const val MAX_DEFENCE = 64.0
    private const val MIN_STAND = PI / 6
    private const val MAX_STAND = PI / 2
}

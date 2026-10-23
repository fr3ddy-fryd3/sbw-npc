package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.hypot

/**
 * How a squad fights for a while: when the [CombatSituation] permits it, what it is worth there,
 * and how it reshapes the members' tasks.
 *
 * The squad HTN domain builds one method per strategy, eligible by [allowed], ranked by [score]
 * ([SquadManners]) and applying [shape]. It never branches on which strategy it holds, so a new
 * strategy is one more implementation and one more [CombatStrategy] entry.
 */
interface SquadStrategy {
    /** Why the strategy is chosen, or ruled out when not [allowed], as the evaluation reports it. */
    val reason: String

    fun allowed(situation: CombatSituation): Boolean

    /** Its utility in [situation], from 0 to 1. */
    fun score(situation: CombatSituation): Double

    /**
     * A member's [intent] as this strategy has it carried out, with what the squad keeps for it in
     * [shaping]. A member assaulting for the operation is never passed in.
     */
    fun shape(intent: PositionIntent, shaping: SquadShaping): PositionIntent
}

/** The squad strategies, by name; each carries its [rules]. */
enum class CombatStrategy(val rules: SquadStrategy) {
    ADVANCE(Advance),
    HOLD(Hold),
    GAIN_HEIGHT(GainHeight),
    WITHDRAW(Withdraw),
    BOUND_OVERWATCH(BoundingOverwatch),
    REGROUP(Regroup),
}

/** Go on with the tasks the team planned, while the attack can be carried on or nobody is in contact. */
private object Advance : SquadStrategy {
    override val reason = "advance_capacity"

    override fun allowed(situation: CombatSituation) = (situation.offensive && situation.continuation) || !situation.contact

    override fun score(situation: CombatSituation) = BASE + situation.readyFraction * READINESS

    override fun shape(intent: PositionIntent, shaping: SquadShaping) = intent

    private const val BASE = 0.55

    /** Worth up to this much more the more of the squad is ready to fire. */
    private const val READINESS = 0.2
}

/** Fire from where the men stand instead of moving; the default when defending in contact. */
private object Hold : SquadStrategy {
    override val reason = "hold_sector"

    override fun allowed(situation: CombatSituation) = true

    override fun score(situation: CombatSituation) = if (!situation.offensive && situation.contact) DEFENDING else OTHERWISE

    override fun shape(intent: PositionIntent, shaping: SquadShaping) = if (intent.duty == CombatDuty.MOVE) intent.copy(duty = CombatDuty.FIRE, anchor = intent.origin, requireCover = false) else intent

    private const val DEFENDING = 0.9
    private const val OTHERWISE = 0.4
}

/** An attack under an enemy above it takes posts no lower than where the men stand, preferring higher ones. */
private object GainHeight : SquadStrategy {
    override val reason = "enemy_above"

    override fun allowed(situation: CombatSituation) = situation.contact && situation.higherEnemy && situation.offensive

    override fun score(situation: CombatSituation) = SCORE

    override fun shape(intent: PositionIntent, shaping: SquadShaping) = intent.copy(minimumHeight = intent.origin.y - DROP, elevationWeight = ELEVATION_WEIGHT)

    private const val SCORE = 0.93

    /** How far below where he stands a man may still take a post. */
    private const val DROP = 2

    private const val ELEVATION_WEIGHT = 3.0
}

/** Under fire after heavy losses with no attack to carry on, the men fall back 48 blocks from the threat. */
private object Withdraw : SquadStrategy {
    override val reason = "losses_without_continuation"

    override fun allowed(situation: CombatSituation) = situation.incoming && situation.lossFraction >= LOSSES && !situation.continuation && !situation.reinforced

    override fun score(situation: CombatSituation) = SCORE

    override fun shape(intent: PositionIntent, shaping: SquadShaping): PositionIntent {
        if (intent.duty == CombatDuty.FOLLOW_ORDER) return intent
        val dx = intent.origin.x - (intent.focus?.x ?: intent.anchor.x)
        val dz = intent.origin.z - (intent.focus?.z ?: intent.anchor.z + 1)
        val length = hypot(dx, dz).coerceAtLeast(MIN_LENGTH)
        return intent.copy(
            duty = CombatDuty.MOVE,
            anchor = Point3(intent.origin.x + dx / length * DISTANCE, intent.origin.y, intent.origin.z + dz / length * DISTANCE),
            requireCover = false,
            requireShelter = false,
            exposureWeight = EXPOSURE_WEIGHT,
            // A sentry withdraws from his height too: kept, the floor refused every post below it.
            minimumHeight = null,
            // Falling back is one run, not a fight from post to post.
            legs = false,
            elevationWeight = 0.0,
        )
    }

    private const val SCORE = 0.95

    /** Keeps a man standing on the threat's bearing from dividing by zero. */
    private const val MIN_LENGTH = 0.01

    /** The share of the squad lost before it may withdraw. */
    private const val LOSSES = 0.4

    private const val DISTANCE = 48

    /** Out of sight counts for much more than distance on the way back. */
    private const val EXPOSURE_WEIGHT = 10.0
}

/**
 * An attack under fire advances by halves: one half rushes from post to post while the other fires
 * from where it stands, then they swap ([BoundTurn]).
 *
 * Only the movers that advance by legs take part; the squad needs at least [MIN_MOVERS] of them so
 * that each half has two. Each rush of the moving half waits for the firing half's fire
 * ([PositionIntent.coveredBy]) instead of needing covering fire for the whole way, as
 * [PositionIntent.requireCover] would: fire that lapsed mid-rush would stop him in the open.
 */
private object BoundingOverwatch : SquadStrategy {
    override val reason = "bound_under_fire"

    // An enemy above is GAIN_HEIGHT's: held, bounding could outscore it there. A scattered squad
    // has no halves to cover each other, and held, bounding kept it from regrouping.
    override fun allowed(situation: CombatSituation) = situation.contact && situation.offensive && situation.continuation && !situation.higherEnemy && !situation.scattered && situation.movers >= MIN_MOVERS

    override fun score(situation: CombatSituation) = (if (situation.underFire) UNDER_FIRE else UNTOUCHED) + situation.readyFraction * READINESS

    override fun shape(intent: PositionIntent, shaping: SquadShaping): PositionIntent {
        val turn = shaping.turn
        if (turn == null || !intent.legs || intent.duty != CombatDuty.MOVE) return intent
        // The other half's fire is his cover: kept, the need for shelter at the end of each leg
        // refused every post on open ground, and the half never moved.
        if (intent.actor.id in turn.moving) return intent.copy(requireCover = false, requireShelter = false, coveredBy = turn.posts.keys)
        val post = turn.posts[intent.actor.id] ?: intent.origin
        return intent.copy(duty = CombatDuty.FIRE, anchor = post, requireCover = false, legs = false)
    }

    const val MIN_MOVERS = 4

    // Under fire it outscores an Advance held (0.67 + 0.2 per readiness) once more than half the
    // squad is ready to fire. With nobody hit for 13 s it never does, even held: bounds would only
    // slow the advance down.
    private const val UNDER_FIRE = 0.62
    private const val UNTOUCHED = 0.3
    private const val READINESS = 0.3
}

/**
 * A scattered attack gathers at a rally point behind it ([SquadShaping.rally]) before it goes on.
 *
 * Only while it does not flank: a manoeuvre group round the flank is far from the rest on purpose.
 * Once begun it goes on until every man is back near the squad ([CombatSituation.gathered]), then
 * the squad chooses again. Ended as soon as it was no longer scattered, the squad spread out
 * again and regrouped over and over.
 */
private object Regroup : SquadStrategy {
    override val reason = "scattered"

    override fun allowed(situation: CombatSituation) = situation.offensive && !situation.flanking && !situation.gathered && (situation.scattered || situation.regrouping)

    override fun score(situation: CombatSituation) = SCORE

    override fun shape(intent: PositionIntent, shaping: SquadShaping): PositionIntent {
        val rally = shaping.rally
        if (rally == null || intent.duty == CombatDuty.FOLLOW_ORDER || intent.duty == CombatDuty.SEARCH) return intent
        return intent.copy(duty = CombatDuty.MOVE, anchor = rally, legs = false, minimumHeight = null, elevationWeight = 0.0)
    }

    // Above an advance held by a squad all of whose men can fight (0.67 + 0.2 at readiness 1): at
    // 0.8, below it, a squad that could fight never regrouped.
    private const val SCORE = 0.9
}

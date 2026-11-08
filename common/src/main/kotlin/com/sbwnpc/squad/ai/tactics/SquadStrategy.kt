package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.Consideration
import com.sbwnpc.squad.ai.planning.ResponseCurve
import com.sbwnpc.squad.ai.planning.UtilityScore
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

    /**
     * Its utility in [situation], from 0 to 1: a base from the bare situation times the
     * considerations of the [CombatSituation.assessment]. With nothing assessed it is the base.
     */
    fun score(situation: CombatSituation): UtilityScore

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

/**
 * Go on with the tasks the team planned, while the attack can be carried on, nobody is in contact,
 * or the squad falls back: a retreat out of [BoundingOverwatch]'s reach is one run to the rally point.
 */
private object Advance : SquadStrategy {
    override val reason = "advance_capacity"

    override fun allowed(situation: CombatSituation) = (situation.offensive && situation.continuation) || !situation.contact || situation.retreating

    // Out of ammunition an attack does not advance: it holds while the men fetch more. A retreat
    // runs whatever it carries.
    override fun score(situation: CombatSituation): UtilityScore {
        val a = situation.assessment
        return UtilityScore.product(
            BASE + situation.readyFraction * READINESS,
            listOf(
                Consideration("ammo", a.ammo.takeIf { situation.offensive && !situation.retreating }, ResponseCurve.linear(0.0, FULL_AMMO)),
                Consideration("force", a.forceRatio, ResponseCurve.floored(OUTNUMBERED_FLOOR, OUTNUMBERED, EVEN)),
                Consideration("health", a.health, ResponseCurve.floored(WOUNDED_FLOOR, WOUNDED, HEALTHY)),
            ),
            reason,
        )
    }

    // A retreat with no halves to cover each other runs the whole way: stopping post by post
    // with nobody firing for him, a man of a small squad only gave the enemy more time to shoot.
    override fun shape(intent: PositionIntent, shaping: SquadShaping) = if (shaping.retreating && intent.legs) intent.copy(legs = false) else intent

    private const val BASE = 0.55

    /** Worth up to this much more the more of the squad is ready to fire. */
    private const val READINESS = 0.2

    /** Half the men still on their main weapon is ammunition enough to push on. */
    private const val FULL_AMMO = 0.5
    private const val OUTNUMBERED_FLOOR = 0.6
    private const val OUTNUMBERED = 0.5
    private const val EVEN = 1.5
    private const val WOUNDED_FLOOR = 0.7
    private const val WOUNDED = 0.4
    private const val HEALTHY = 0.8
}

/**
 * Fire from where the men stand instead of moving; the default when defending in contact. Never in
 * a retreat: it would stand the men still where they were ordered away from.
 */
private object Hold : SquadStrategy {
    override val reason = "hold_sector"

    override fun allowed(situation: CombatSituation) = !situation.retreating

    // Outnumbered, a squad holds; much stronger, holding is worth less. Long after the last
    // sighting, standing still is worth less than going on.
    override fun score(situation: CombatSituation): UtilityScore {
        val a = situation.assessment
        return UtilityScore.product(
            if (!situation.offensive && situation.contact) DEFENDING else OTHERWISE,
            listOf(
                Consideration("force", a.forceRatio, ResponseCurve.floored(STRONG_FLOOR, STRONG, EVEN)),
                Consideration("since_contact", a.sinceContact, ResponseCurve.floored(STALE_FLOOR, STALE_SECONDS, FRESH_SECONDS)),
            ),
            reason,
        )
    }

    override fun shape(intent: PositionIntent, shaping: SquadShaping) = if (intent.duty == CombatDuty.MOVE) intent.copy(duty = CombatDuty.FIRE, anchor = intent.origin, requireCover = false) else intent

    private const val DEFENDING = 0.9
    private const val OTHERWISE = 0.4
    private const val STRONG_FLOOR = 0.7
    private const val STRONG = 2.5
    private const val EVEN = 1.0
    private const val STALE_FLOOR = 0.6
    private const val STALE_SECONDS = 40.0
    private const val FRESH_SECONDS = 10.0
}

/** An attack under an enemy above it takes posts no lower than where the men stand, preferring higher ones. */
private object GainHeight : SquadStrategy {
    override val reason = "enemy_above"

    override fun allowed(situation: CombatSituation) = situation.contact && situation.higherEnemy && situation.offensive

    override fun score(situation: CombatSituation) = UtilityScore.product(SCORE, listOf(Consideration("force", situation.assessment.forceRatio, ResponseCurve.floored(OUTNUMBERED_FLOOR, OUTNUMBERED, EVEN))), reason)

    override fun shape(intent: PositionIntent, shaping: SquadShaping) = intent.copy(minimumHeight = intent.origin.y - DROP, elevationWeight = ELEVATION_WEIGHT)

    private const val SCORE = 0.93
    private const val OUTNUMBERED_FLOOR = 0.8
    private const val OUTNUMBERED = 0.5
    private const val EVEN = 1.2

    /** How far below where he stands a man may still take a post. */
    private const val DROP = 2

    private const val ELEVATION_WEIGHT = 3.0
}

/** Under fire after heavy losses with no attack to carry on, the men fall back 48 blocks from the threat. */
private object Withdraw : SquadStrategy {
    override val reason = "losses_without_continuation"

    // A retreat already falls back, to the point it was ordered to rather than away from the threat.
    override fun allowed(situation: CombatSituation) = situation.incoming && situation.lossFraction >= LOSSES && !situation.continuation && !situation.reinforced && !situation.retreating

    // The 40% losses stay a hard limit (allowed); past it, the weaker the squad against the enemy
    // and the less it has to fight with, the more falling back is worth.
    override fun score(situation: CombatSituation): UtilityScore {
        val a = situation.assessment
        return UtilityScore.product(
            SCORE,
            listOf(
                Consideration("force", a.forceRatio, ResponseCurve.floored(STRONG_FLOOR, STRONG, OUTNUMBERED)),
                Consideration("ammo", a.ammo, ResponseCurve.floored(SUPPLIED_FLOOR, SUPPLIED, DRY)),
            ),
            reason,
        )
    }

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
    private const val STRONG_FLOOR = 0.5
    private const val STRONG = 1.5
    private const val OUTNUMBERED = 0.5
    private const val SUPPLIED_FLOOR = 0.8
    private const val SUPPLIED = 0.8
    private const val DRY = 0.2

    /** Keeps a man standing on the threat's bearing from dividing by zero. */
    private const val MIN_LENGTH = 0.01

    /** The share of the squad lost before it may withdraw. */
    private const val LOSSES = 0.4

    private const val DISTANCE = 48

    /** Out of sight counts for much more than distance on the way back. */
    private const val EXPOSURE_WEIGHT = 10.0
}

/**
 * An attack under fire advances by halves, and a retreat in contact falls back by them (#253): one
 * half rushes from post to post while the other fires from where it stands, then they swap ([BoundTurn]).
 *
 * Only the movers that advance by legs take part; the squad needs at least [MIN_MOVERS] of them so
 * that each half has two. Each rush of the moving half waits for the firing half's fire
 * ([PositionIntent.coveredBy]) instead of needing covering fire for the whole way, as
 * [PositionIntent.requireCover] would: fire that lapsed mid-rush would stop him in the open.
 */
private object BoundingOverwatch : SquadStrategy {
    override val reason = "bound_under_fire"

    // An enemy above is GAIN_HEIGHT's: held, bounding could outscore it there. A scattered squad
    // has no halves to cover each other, and held, bounding kept it from regrouping. A squad whose
    // halves did not fire for a rush advances cover to cover instead of waiting on them.
    override fun allowed(situation: CombatSituation) =
        situation.contact && !situation.scattered && !situation.coverStalled && situation.movers >= MIN_MOVERS &&
            (situation.retreating || (situation.offensive && situation.continuation && !situation.higherEnemy))

    // Falling back in contact it always outscores an Advance, hit or not: the halves covering each
    // other is the point of a fighting withdrawal, and the run is for a squad out of contact.
    // No considerations yet: under fire it outscores an Advance held by a small margin, and any
    // factor below 1 (a weak enemy, sheltered ground) cost it that margin in the bounding scene.
    override fun score(situation: CombatSituation) = UtilityScore.product((if (situation.underFire || situation.retreating) UNDER_FIRE else UNTOUCHED) + situation.readyFraction * READINESS, emptyList(), reason)

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
 * again and regrouped over and over. It ends after 20 s anyway and does not come back for 30 s
 * ([CombatSituation.regroupSpent]): a squad that could not gather regrouped until the fight ended.
 */
private object Regroup : SquadStrategy {
    override val reason = "scattered"

    override fun allowed(situation: CombatSituation) = situation.offensive && !situation.flanking && !situation.gathered && !situation.regroupSpent && (situation.scattered || situation.regrouping)

    override fun score(situation: CombatSituation) = UtilityScore.product(SCORE, emptyList(), reason)

    override fun shape(intent: PositionIntent, shaping: SquadShaping): PositionIntent {
        val rally = shaping.rally
        if (rally == null || intent.duty == CombatDuty.FOLLOW_ORDER || intent.duty == CombatDuty.SEARCH) return intent
        // No shelter asked of the rally: on open ground under fire every post round it was refused
        // for want of one, and the men stayed scattered for the whole regroup.
        return intent.copy(duty = CombatDuty.MOVE, anchor = rally, legs = false, minimumHeight = null, elevationWeight = 0.0, requireShelter = false)
    }

    // Above an advance held by a squad all of whose men can fight (0.67 + 0.2 at readiness 1): at
    // 0.8, below it, a squad that could fight never regrouped.
    private const val SCORE = 0.9
}

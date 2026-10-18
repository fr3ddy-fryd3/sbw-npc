package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.hypot

/**
 * How a squad fights for a while: when the [CombatSituation] permits it, what it is worth there,
 * and how it reshapes the members' tasks.
 *
 * [StrategyEvaluation] ranks the strategies by [allowed] and [score]; the squad HTN domain builds
 * one method per strategy that applies [shape]. Neither branches on which strategy it holds, so a
 * new strategy is one more implementation and one more [CombatStrategy] entry.
 */
interface SquadStrategy {
    /** Why the strategy is chosen, or ruled out when not [allowed], as the evaluation reports it. */
    val reason: String

    fun allowed(situation: CombatSituation): Boolean

    /** Its utility in [situation], from 0 to 1. */
    fun score(situation: CombatSituation): Double

    /** A member's [intent] as this strategy has it carried out. A member assaulting for the operation is never passed in. */
    fun shape(intent: PositionIntent): PositionIntent
}

/** The squad strategies, by name; each carries its [rules]. */
enum class CombatStrategy(val rules: SquadStrategy) {
    ADVANCE(Advance),
    HOLD(Hold),
    GAIN_HEIGHT(GainHeight),
    WITHDRAW(Withdraw),
}

/** Go on with the tasks the team planned, while the attack can be carried on or nobody is in contact. */
private object Advance : SquadStrategy {
    override val reason = "advance_capacity"

    override fun allowed(situation: CombatSituation) = (situation.offensive && situation.continuation) || !situation.contact

    override fun score(situation: CombatSituation) = BASE + situation.readyFraction * READINESS

    override fun shape(intent: PositionIntent) = intent

    private const val BASE = 0.55

    /** Worth up to this much more the more of the squad is ready to fire. */
    private const val READINESS = 0.2
}

/** Fire from where the men stand instead of moving; the default when defending in contact. */
private object Hold : SquadStrategy {
    override val reason = "hold_sector"

    override fun allowed(situation: CombatSituation) = true

    override fun score(situation: CombatSituation) = if (!situation.offensive && situation.contact) DEFENDING else OTHERWISE

    override fun shape(intent: PositionIntent) = if (intent.duty == CombatDuty.MOVE) intent.copy(duty = CombatDuty.FIRE, anchor = intent.origin, requireCover = false) else intent

    private const val DEFENDING = 0.9
    private const val OTHERWISE = 0.4
}

/** An attack under an enemy above it takes posts no lower than where the men stand, preferring higher ones. */
private object GainHeight : SquadStrategy {
    override val reason = "enemy_above"

    override fun allowed(situation: CombatSituation) = situation.contact && situation.higherEnemy && situation.offensive

    override fun score(situation: CombatSituation) = SCORE

    override fun shape(intent: PositionIntent) = intent.copy(minimumHeight = intent.origin.y - DROP, elevationWeight = ELEVATION_WEIGHT)

    private const val SCORE = 0.93

    /** How far below where he stands a man may still take a post. */
    private const val DROP = 2

    private const val ELEVATION_WEIGHT = 3.0
}

/** Under fire after heavy losses with no attack to carry on, the men fall back 48 blocks from the threat. */
private object Withdraw : SquadStrategy {
    override val reason = "losses_without_continuation"

    override fun allowed(situation: CombatSituation) = situation.incoming && situation.lossFraction >= LOSSES && !situation.continuation

    override fun score(situation: CombatSituation) = SCORE

    override fun shape(intent: PositionIntent): PositionIntent {
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

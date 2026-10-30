package com.sbwnpc.squad.ai.tactics

/** How an attacking operation stands, by [CombatStrength]. */
enum class OperationStanding {
    STEADY,

    /** The operation stops pushing forward and holds its firing line; an assault still needs the usual superiority. */
    REVIEW,

    /** The operation calls the attack off and falls back. */
    WITHDRAW,
}

/**
 * One measure of what an attack can still do (#238): a man counts while he is alive and has
 * something to fire, so a squad that has shot itself dry has lost as much as one that has lost its
 * men. It replaced two rules that did not know of each other, one counting the dead of an
 * operation and one aborting a squad with half its rifles empty.
 *
 * An attack is measured once, when it starts. Its standing is then the share of that base it has
 * lost, so a squad sent in at half strength is judged by what it had, not by its full complement.
 * A squad sent in as the remnant of one is not judged at all: it was sent knowing what was left.
 * No world access.
 */
object CombatStrength {
    /** Below this share of its full complement still alive, a squad attacks as a remnant. */
    const val REMNANT_SHARE = 1.0 / 3.0

    /** This share of the base lost holds the attack on its firing line. */
    const val HOLD_AT = 0.3

    /** This share of the base lost calls the attack off. */
    const val WITHDRAW_AT = 0.6

    /** A squad's infantry: [full] at deployment, [living] now, [able] of them with something to fire. */
    data class Count(val full: Int, val living: Int, val able: Int) {
        val dry get() = living - able
    }

    /** How a squad goes into an attack. */
    enum class Start {
        /** Fights to the end, never withdrawn on strength. */
        REMNANT,

        /** Most of its living men have nothing to fire and a Supply is known: it refills first. */
        RESUPPLY,

        /** Attacks at the strength it has, the base its losses are measured against. */
        ATTACK,
    }

    /** Remnant first: a remnant is sent to fight, not to refill. [canResupply] says a Supply is known and not yet visited. */
    fun start(count: Count, canResupply: Boolean): Start =
        when {
            count.living < count.full * REMNANT_SHARE -> Start.REMNANT
            canResupply && count.living > 0 && count.dry * 2 >= count.living -> Start.RESUPPLY
            else -> Start.ATTACK
        }

    /** Whether the squad goes in with half or more of its living men dry: the player is warned. */
    fun poorChances(count: Count): Boolean = count.living > 0 && count.dry * 2 >= count.living

    /** The standing of an attack with [able] men left of its [base]; an attack with no base has nothing to lose. */
    fun standing(able: Int, base: Int): OperationStanding {
        if (base <= 0) return OperationStanding.STEADY
        val lost = 1.0 - able.toDouble() / base
        return when {
            lost >= WITHDRAW_AT -> OperationStanding.WITHDRAW
            lost >= HOLD_AT -> OperationStanding.REVIEW
            else -> OperationStanding.STEADY
        }
    }
}

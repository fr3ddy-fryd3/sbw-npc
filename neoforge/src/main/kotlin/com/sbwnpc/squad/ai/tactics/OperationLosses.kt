package com.sbwnpc.squad.ai.tactics

import java.util.UUID

/** How an attacking operation stands after its losses. */
enum class OperationStanding {
    STEADY,

    /** The operation stops pushing forward and holds its firing line; an assault still needs the usual superiority. */
    REVIEW,

    /** The operation calls the attack off and falls back. */
    WITHDRAW,
}

/**
 * Losses of one operation, counted over everyone who has fought in it rather than per squad.
 *
 * Every member seen in a participating squad counts towards its strength; one no longer in any
 * participating squad counts as lost. A squad that leaves the operation takes its record with it,
 * and fresh men from a reinforcement lower the fraction again. No world access.
 */
class OperationLosses {
    private val seen = HashMap<UUID, MutableSet<UUID>>()

    /** Records [members] of [squad] as part of the operation now. */
    fun observe(squad: UUID, members: Collection<UUID>) {
        seen.getOrPut(squad, ::HashSet) += members
    }

    /** Forgets squads no longer in the operation. */
    fun retain(squads: Set<UUID>) {
        seen.keys.retainAll(squads)
    }

    /** Fraction of the operation's strength lost, given each squad's [current] members. */
    fun fraction(current: (UUID) -> Collection<UUID>): Double {
        val all = seen.values.flatMapTo(HashSet()) { it }
        if (all.isEmpty()) return 0.0
        val present = seen.keys.flatMapTo(HashSet()) { current(it) }
        return all.count { it !in present }.toDouble() / all.size
    }

    companion object {
        const val REVIEW_AT = 0.3
        const val WITHDRAW_AT = 0.6

        fun standing(fraction: Double) =
            when {
                fraction >= WITHDRAW_AT -> OperationStanding.WITHDRAW
                fraction >= REVIEW_AT -> OperationStanding.REVIEW
                else -> OperationStanding.STEADY
            }
    }
}

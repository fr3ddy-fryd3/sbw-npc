package com.sbwnpc.squad.ai.tactics

/**
 * How long a remembered enemy position keeps an area from counting as checked once nobody sees
 * the enemy there any more. A scout that cannot reach the spot otherwise held the objective open
 * for good: in-game 16.10 an attack sat on its taken point for six minutes over one position
 * 89 blocks away that no route led to.
 */
object UnverifiedContacts {
    /** Unseen this long (30 s), a position away from the objective no longer needs checking. */
    const val FAR_TICKS = 600L

    /** Unseen this long (2 min), a position at the objective no longer needs checking either. */
    const val NEAR_TICKS = 2400L

    fun expired(distanceToObjective: Double, ticksUnseen: Long): Boolean = ticksUnseen > if (distanceToObjective > AttackPhase.OBJECTIVE_RADIUS) FAR_TICKS else NEAR_TICKS
}

package com.sbwnpc.squad.ai.tactics

/**
 * When a reinforcement on its way to an operation stops marching to fight.
 *
 * It sees every contact of the operation it is joining, and any of them used to count as contact:
 * in-game 16.10 a squad 200 blocks out took an enemy 280 m away for its fight, hopped 12 blocks
 * at a time towards it and stood stuck for over a minute while the attack it was sent to back up
 * fell back to deployment.
 */
object ReinforcementMarch {
    /** An enemy this close to one of its men is the squad's own fight. */
    const val ENGAGE_RADIUS = AttackPhase.OBJECTIVE_RADIUS

    /**
     * A fighting squad marches on only once the enemy is this far. With one line for both, an
     * enemy at about 48 blocks switched the squad between marching and fighting every few seconds
     * in-game 16.10, and each switch stopped every man to pick a new post.
     */
    const val DISENGAGE_RADIUS = 64.0

    /** Unfired on this long (30 s), a squad stopped by fire marches on. */
    const val QUIET_TICKS = 600L

    fun engaged(nearestContact: Double?, ticksSinceIncoming: Long, fighting: Boolean = false): Boolean = ticksSinceIncoming < QUIET_TICKS || (nearestContact != null && nearestContact <= if (fighting) DISENGAGE_RADIUS else ENGAGE_RADIUS)
}

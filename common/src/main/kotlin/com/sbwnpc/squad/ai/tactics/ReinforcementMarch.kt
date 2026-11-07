package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.hypot

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

    /**
     * An attack is joined within this of its objective, from any side: coming in from the far side
     * encircles the enemy, and a narrow circle on the side the first squad came from was missed
     * once the fight bent the reinforcement's path.
     */
    const val JOIN_RADIUS = AttackPhase.OBJECTIVE_RADIUS + 32.0

    /** How far short of the objective a marching reinforcement makes for: inside [JOIN_RADIUS], never onto the enemy. */
    const val STAND_OFF = AttackPhase.OBJECTIVE_RADIUS

    /**
     * Where a reinforcement at [from] marches to join the attack on [objective]: [STAND_OFF] short
     * of it on its own line of approach, or null when it already stands that close, so it is not
     * sent back out (left over [JOIN_RADIUS] off in height, it would walk to and fro).
     */
    fun marchPoint(objective: Point3, from: Point3): Point3? {
        val dx = from.x - objective.x
        val dz = from.z - objective.z
        val distance = hypot(dx, dz)
        if (distance <= STAND_OFF) return null
        return Point3(objective.x + dx / distance * STAND_OFF, objective.y, objective.z + dz / distance * STAND_OFF)
    }

    fun engaged(nearestContact: Double?, ticksSinceIncoming: Long, fighting: Boolean = false): Boolean = ticksSinceIncoming < QUIET_TICKS || (nearestContact != null && nearestContact <= if (fighting) DISENGAGE_RADIUS else ENGAGE_RADIUS)

    /**
     * How far the operation's nearest contact is from the nearest of the squad's [members], for
     * [engaged]: null without contacts. Measured in three dimensions.
     */
    fun nearestContact(contacts: List<Point3>, members: List<Point3>): Double? = contacts.minOfOrNull { contact -> members.minOfOrNull { it.distanceTo(contact) } ?: Double.MAX_VALUE }
}

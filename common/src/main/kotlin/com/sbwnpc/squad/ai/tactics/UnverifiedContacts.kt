package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID

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

    /** Scouts check remembered positions this close to the operation's point, nearest first. */
    const val SCOUT_RADIUS = 64.0

    /**
     * The remembered position a scout checks: the nearest to [anchor] within [SCOUT_RADIUS].
     * The first remembered one, however far, sent scouts 89 blocks out after a taken point, where
     * no route led and they stood for six minutes.
     */
    fun scoutTarget(anchor: Point3, remembered: Collection<Point3>): Point3? = remembered.map { it to Math.sqrt(it.horizontalDistanceSquared(anchor)) }.filter { it.second <= SCOUT_RADIUS }.minByOrNull { it.second }?.first

    /** Men sent to check a position at a time. */
    const val SCOUTS = 2

    /**
     * Where an operation's scouts look round [anchor], see [inspectAnchor]: off contact, the
     * remembered position from [scoutTarget], else the objective of an attack still under way.
     * Nothing is checked in a fight or by a reinforcement still marching past one.
     */
    fun inspectTarget(inContact: Boolean, marching: Boolean, anchor: Point3, remembered: Collection<Point3>, activeAttackObjective: Point3?): Point3? = if (inContact || marching) null else scoutTarget(anchor, remembered) ?: activeAttackObjective

    /** What the remembered positions are measured from: the area being taken, else the order's objective, else the men. */
    fun inspectAnchor(areaObjective: Point3?, orderObjective: Point3?, centre: Point3): Point3 = areaObjective ?: orderObjective ?: centre

    /**
     * The [SCOUTS] ready men sent to [inspect], or none without one. Men already scouting keep it,
     * so the pair is not swapped each layout; the rest go nearest first. Ties keep [candidates]' order.
     */
    fun scouts(candidates: List<Pair<UUID, Point3>>, current: Set<UUID>, inspect: Point3?): Set<UUID> {
        if (inspect == null) return emptySet()
        return candidates.sortedWith(compareBy<Pair<UUID, Point3>> { it.first !in current }.thenBy { it.second.distanceTo(inspect) }).take(SCOUTS).mapTo(HashSet()) { it.first }
    }
}

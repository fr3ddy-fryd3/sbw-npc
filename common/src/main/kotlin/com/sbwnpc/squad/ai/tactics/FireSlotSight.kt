package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.hypot

/**
 * Moves a firing-line slot to a nearby point that can see the defence.
 *
 * [OperationFront] places a slot by distance alone, so one behind a ridge or in a hollow keeps its
 * man standing there, too close to leave and unable to fire (#93).
 */
object FireSlotSight {
    private const val SIDE_STEP = 9.0

    /** Sideways steps tried at each depth, nearest to the slot first. */
    private val LATERALS = doubleArrayOf(0.0, SIDE_STEP, -SIDE_STEP, SIDE_STEP + SIDE_STEP, -SIDE_STEP - SIDE_STEP)

    /** Closer than this the slot sits on the defender and has no direction to move along. */
    private const val MIN_DISTANCE = 0.01

    /** How far inside the clearance circle the nearest depth stays, so the man does not stand on its edge. */
    private const val CLEARANCE_MARGIN = 2.0

    /** Sideways steps of the wider search, after the band round the slot found nothing. */
    private const val WIDE_SIDE_STEPS = 4
    private val WIDE_LATERALS = (0..WIDE_SIDE_STEPS).flatMap { listOf(it * SIDE_STEP, -it * SIDE_STEP) }.distinct()

    /** How far behind the slot, in steps of [DEPTH_STEP], the wider search goes. */
    private const val BACK_STEPS = 4
    private const val DEPTH_STEP = 8.0

    /**
     * The nearest a blind slot is brought to the defence when nothing outside the clearance sees
     * it (owner, 24.10): in a hollow below a hill, every point of the band looked into the slope.
     */
    const val BLIND_MIN_DISTANCE = 32.0

    /** Defenders, nearest first, any of whom a candidate may see to count as sighted. */
    private const val SIGHTED_DEFENDERS = 3

    /** A slot that moved less than this since it was searched keeps its chosen post. */
    private const val REUSE_DISTANCE = 4.0

    /** A slot that found no sighted post is searched again after this, as the defence and the men move. */
    private const val RETRY_TICKS = 40L

    /**
     * Whether a search made [age] ticks ago for a slot that has since moved [moved] blocks still
     * answers for it. A post it [found] is kept, so a man is not shuffled about by every re-layout;
     * a miss is searched again shortly instead, or the man stays on a blind post until the whole
     * line moves.
     */
    fun stillAnswers(moved: Double, found: Boolean, age: Long): Boolean = moved <= REUSE_DISTANCE && (found || age < RETRY_TICKS)

    /**
     * A post that sees the defence. [clearance] is set when it lies inside the role's clearance and
     * the man may come that near; null keeps the role's own.
     */
    data class Sighted(val post: Point3, val clearance: Double? = null)

    /**
     * The first candidate round [slot] from which [sees] holds towards the defence, or null if none
     * does. The slot itself is tried first, so a slot that already sees the defence does not move.
     *
     * The search widens in three rounds:
     * - the band between [clearance] and the slot's own distance from the nearest defender, where
     *   firing is allowed, a little to each side. A candidate that sees any of the nearest few men
     *   sees the defence: one tree or wall in front of the nearest alone does not make it blind;
     * - farther back behind the slot and wider to each side;
     * - nearer than [clearance], down to [BLIND_MIN_DISTANCE], with the nearer clearance returned.
     *   In-game 23.10 slots in a hollow 13-25 blocks below the defence found no point in the band,
     *   and a third of the line stood unable to fire.
     *
     * The wider rounds look at the nearest defender only, to keep the extra raycasts down.
     */
    fun choose(slot: Point3, defenders: List<Point3>, clearance: Double, sees: (post: Point3, enemy: Point3) -> Boolean): Sighted? {
        val nearest = defenders.sortedBy { hypot(it.x - slot.x, it.z - slot.z) }.take(SIGHTED_DEFENDERS)
        val enemy = nearest.firstOrNull() ?: return Sighted(slot)
        val distance = hypot(slot.x - enemy.x, slot.z - enemy.z)
        if (distance < MIN_DISTANCE) return Sighted(slot)
        val line = Line(enemy, (slot.x - enemy.x) / distance, (slot.z - enemy.z) / distance, slot.y)
        return candidates(line, distance, clearance + CLEARANCE_MARGIN)
            .firstOrNull { if (it.anyDefender) nearest.any { defender -> sees(it.sighted.post, defender) } else sees(it.sighted.post, enemy) }
            ?.sighted
    }

    /** From [enemy] out along the unit direction ([ux], [uz]) towards the slot, at the slot's height [y]. */
    private class Line(val enemy: Point3, val ux: Double, val uz: Double, val y: Double) {
        fun at(depth: Double, lateral: Double) = Point3(enemy.x + ux * depth - uz * lateral, y, enemy.z + uz * depth + ux * lateral)
    }

    /** A post to try, and whether seeing any of the nearest defenders, not only the nearest, will do. */
    private class Candidate(val sighted: Sighted, val anyDefender: Boolean)

    /** Every round's candidates in turn, see [choose]; [closest] is the nearest depth outside the clearance. */
    private fun candidates(line: Line, distance: Double, closest: Double): Sequence<Candidate> =
        sequence {
            val band = if (distance <= closest) listOf(distance) else listOf(distance, (distance + closest) / 2.0, closest)
            for (depth in band) for (lateral in LATERALS) yield(Candidate(Sighted(line.at(depth, lateral)), anyDefender = true))
            for (step in 1..BACK_STEPS) for (lateral in WIDE_LATERALS) yield(Candidate(Sighted(line.at(distance + step * DEPTH_STEP, lateral)), anyDefender = false))
            val wider = WIDE_LATERALS.filter { wide -> LATERALS.none { it == wide } }
            for (depth in band) for (lateral in wider) yield(Candidate(Sighted(line.at(depth, lateral)), anyDefender = false))
            var depth = minOf(distance, closest) - DEPTH_STEP
            while (depth >= BLIND_MIN_DISTANCE) {
                for (lateral in WIDE_LATERALS) yield(Candidate(Sighted(line.at(depth, lateral), depth - CLEARANCE_MARGIN), anyDefender = false))
                depth -= DEPTH_STEP
            }
        }
}

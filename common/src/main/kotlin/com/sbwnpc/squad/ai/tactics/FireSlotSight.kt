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
    private const val SIDE_STEP = 6.0

    /** Sideways steps tried at each depth, nearest to the slot first. */
    private val LATERALS = doubleArrayOf(0.0, SIDE_STEP, -SIDE_STEP, SIDE_STEP + SIDE_STEP, -SIDE_STEP - SIDE_STEP)

    /** Closer than this the slot sits on the defender and has no direction to move along. */
    private const val MIN_DISTANCE = 0.01

    /** How far inside the clearance circle the nearest depth stays, so the man does not stand on its edge. */
    private const val CLEARANCE_MARGIN = 2.0

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
     * The first of a few candidates round [slot] from which [sees] holds towards one of the
     * nearest [defenders], or null if none does. The candidates lie between [clearance] and the
     * slot's own distance from the nearest defender, the band where firing is allowed, and sideways
     * along the line. The slot itself is tried first, so a slot that already sees the defence does
     * not move. A candidate that sees any of the nearest few men sees the defence: one tree or wall
     * in front of the nearest alone does not make it blind.
     */
    fun choose(slot: Point3, defenders: List<Point3>, clearance: Double, sees: (post: Point3, enemy: Point3) -> Boolean): Point3? {
        val nearest = defenders.sortedBy { hypot(it.x - slot.x, it.z - slot.z) }.take(SIGHTED_DEFENDERS)
        val enemy = nearest.firstOrNull() ?: return slot
        val distance = hypot(slot.x - enemy.x, slot.z - enemy.z)
        if (distance < MIN_DISTANCE) return slot
        val ux = (slot.x - enemy.x) / distance
        val uz = (slot.z - enemy.z) / distance
        val closest = clearance + CLEARANCE_MARGIN
        val depths = if (distance <= closest) listOf(distance) else listOf(distance, (distance + closest) / 2.0, closest)
        for (depth in depths) {
            for (lateral in LATERALS) {
                val candidate = Point3(enemy.x + ux * depth - uz * lateral, slot.y, enemy.z + uz * depth + ux * lateral)
                if (nearest.any { sees(candidate, it) }) return candidate
            }
        }
        return null
    }
}

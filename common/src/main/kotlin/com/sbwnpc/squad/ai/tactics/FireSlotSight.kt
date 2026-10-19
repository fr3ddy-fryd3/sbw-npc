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

    /**
     * The first of a few candidates round [slot] from which [sees] holds towards the nearest of
     * [defenders], or null if none does. The candidates lie between [clearance] and the slot's own
     * distance from that defender, the band where firing is allowed, and sideways along the line.
     * The slot itself is tried first, so a slot that already sees the defence does not move.
     */
    fun choose(slot: Point3, defenders: List<Point3>, clearance: Double, sees: (post: Point3, enemy: Point3) -> Boolean): Point3? {
        val enemy = defenders.minByOrNull { hypot(it.x - slot.x, it.z - slot.z) } ?: return slot
        val distance = hypot(slot.x - enemy.x, slot.z - enemy.z)
        if (distance < MIN_DISTANCE) return slot
        val ux = (slot.x - enemy.x) / distance
        val uz = (slot.z - enemy.z) / distance
        val nearest = clearance + CLEARANCE_MARGIN
        val depths = if (distance <= nearest) listOf(distance) else listOf(distance, (distance + nearest) / 2.0, nearest)
        for (depth in depths) {
            for (lateral in LATERALS) {
                val candidate = Point3(enemy.x + ux * depth - uz * lateral, slot.y, enemy.z + uz * depth + ux * lateral)
                if (sees(candidate, enemy)) return candidate
            }
        }
        return null
    }
}

package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Terrain can adjust height and a few blocks of depth, but cannot merge neighboring firing lanes. */
data class TacticalFrontPost(val point: Vec3, val forward: Vec3) {
    private val side = Vec3(-forward.z, 0.0, forward.x)

    fun contains(candidate: Vec3): Boolean = permits(candidate, recovery = false)

    /** Look for a wall along the owned lane before settling for open ground on the nominal line. */
    fun permitsFiringPost(candidate: Vec3, covered: Boolean, recovery: Boolean): Boolean = permits(candidate, recovery) || (covered && permits(candidate, recovery = true))

    /** A failed firing query may adjust depth without taking a neighboring member's lane. */
    fun permits(candidate: Vec3, recovery: Boolean): Boolean {
        val delta = candidate.subtract(point)
        val depth = if (recovery) RECOVERY_DEPTH else DEPTH_TOLERANCE
        return abs(delta.dot(side)) <= LATERAL_TOLERANCE + POSITION_EPSILON && abs(delta.dot(forward)) <= depth + POSITION_EPSILON
    }

    fun acceptsDeployment(from: Vec3, candidate: Vec3, recovery: Boolean = false): Boolean = permits(candidate, recovery) || candidate.subtract(point).horizontalDistance() < from.subtract(point).horizontalDistance() - 0.5

    fun leg(from: Vec3): Vec3 = from.add(point.subtract(from).normalize().scale(minOf(DEPLOYMENT_LEG_BLOCKS, from.distanceTo(point))))

    /** Local block-sized probes in this member's strip, rather than overlapping circular searches. */
    fun probes(recovery: Boolean = false): List<Vec3> {
        val reach = (if (recovery) RECOVERY_DEPTH else DEPTH_TOLERANCE).toInt()
        return (-reach..reach).sortedBy { abs(it) }.flatMap { depth ->
            // Half-block lateral probes survive rounding to block centers on diagonal fronts.
            listOf(0.0, -0.5, 0.5).map { lateral -> point.add(forward.scale(depth.toDouble())).add(side.scale(lateral)) }
        }
    }

    companion object {
        const val LATERAL_TOLERANCE = 1.0
        const val DEPTH_TOLERANCE = 2.0
        const val RECOVERY_DEPTH = 12.0
        const val DEPLOYMENT_LEG_BLOCKS = 24.0
        private const val POSITION_EPSILON = 1.0e-6
    }
}

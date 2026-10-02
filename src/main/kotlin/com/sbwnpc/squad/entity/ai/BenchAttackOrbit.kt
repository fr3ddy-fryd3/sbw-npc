package com.sbwnpc.squad.entity.ai

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Continuous carrot on a circle: fly tangentially, keep the target on the occupied bench side. */
object BenchAttackOrbit {
    const val RADIUS = 40.0
    const val CLEARANCE = 24.0
    const val SPEED = 0.4
    private const val LEAD = Math.PI / 6

    /** Stock AH-6 seat 2 faces +90 degrees; seat 3 faces -90. Seat 1 faces forward. */
    fun direction(positiveBenchOccupied: Boolean, negativeBenchOccupied: Boolean): Int =
        if (!positiveBenchOccupied && negativeBenchOccupied) -1 else 1

    fun waypoint(position: Vec3, center: Vec3, direction: Int, fallbackYaw: Float): Vec3 {
        val dx = position.x - center.x
        val dz = position.z - center.z
        val angle = if (dx * dx + dz * dz < 1.0) Math.toRadians(fallbackYaw.toDouble()) else atan2(dz, dx)
        val ahead = angle + LEAD * direction
        return Vec3(center.x + cos(ahead) * RADIUS, center.y, center.z + sin(ahead) * RADIUS)
    }

    /** Seat arcs come from SBW's AH-6 data. Don't shoot across the cabin or out the back. */
    fun canAim(seat: Int, aircraftYaw: Float, from: Vec3, target: Vec3): Boolean {
        val orientation = when (seat) { 1 -> 0f; 2 -> 90f; 3 -> -90f; else -> return false }
        val relative = Mth.wrapDegrees(HelicopterFlightController.yawToward(from, target) - aircraftYaw - orientation)
        return kotlin.math.abs(relative) <= 80f
    }
}

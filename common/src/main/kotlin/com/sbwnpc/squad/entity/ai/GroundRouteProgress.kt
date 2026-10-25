package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

/** Follow the polyline forward, including when an asynchronous search starts behind the vehicle. */
internal class GroundRouteProgress {
    private var route: List<Vec3> = emptyList()
    private var distances = doubleArrayOf()
    private var leg = 0
    private var along = 0.0
    val remaining: Double get() = (distances.lastOrNull() ?: 0.0) - along

    fun reset(route: List<Vec3>, position: Vec3) {
        this.route = route
        distances = DoubleArray(route.size)
        for (i in 1 until route.size) distances[i] = distances[i - 1] + distance(route[i - 1], route[i])
        leg = 0
        along = 0.0
        project(position, route.size - 1)
    }

    /** Refreshes must lead somewhere from the current position. A one-node or already-passed
     *  answer leaves a usable route intact instead of repeatedly stopping its traveller. */
    fun replaceIfAdvancing(route: List<Vec3>, position: Vec3, radius: Double): Boolean {
        if (route.size < 2) return false
        val replacement = GroundRouteProgress()
        replacement.reset(route, position)
        if (replacement.finished(position, radius)) return false
        this.route = replacement.route
        distances = replacement.distances
        leg = replacement.leg
        along = replacement.along
        return true
    }

    /** At low speed follow nearby ground; at speed retain enough lead for the steering to settle. */
    fun drivingWaypoint(position: Vec3, speed: Double): Vec3? = waypoint(position, (MIN_LOOK_AHEAD + speed * SPEED_LOOK_AHEAD).coerceAtMost(MAX_LOOK_AHEAD))

    fun waypoint(position: Vec3, lookAhead: Double): Vec3? {
        if (route.isEmpty()) return null
        project(position, minOf(route.size - 1, leg + 4))
        val target = along + beforeBend(lookAhead)
        for (i in leg until route.size - 1) {
            if (distances[i + 1] < target) continue
            val length = distances[i + 1] - distances[i]
            val t = if (length < 1e-6) 1.0 else ((target - distances[i]) / length).coerceIn(0.0, 1.0)
            return route[i].lerp(route[i + 1], t)
        }
        return route.last()
    }

    private fun beforeBend(lookAhead: Double): Double {
        val bend = nextBend(lookAhead) ?: return lookAhead
        return minOf(lookAhead, bend + TURN_LEAD)
    }

    private fun nextBend(reach: Double): Double? {
        for (i in leg + 1 until route.lastIndex) {
            val ahead = distances[i] - along
            if (ahead > reach) break
            if (ahead <= TURN_REACHED_RADIUS) continue
            val incoming = route[i].subtract(route[i - 1])
            val outgoing = route[i + 1].subtract(route[i])
            val length = incoming.horizontalDistance() * outgoing.horizontalDistance()
            if (length > 0 && (incoming.x * outgoing.x + incoming.z * outgoing.z) / length < TURN_COSINE) return ahead
        }
        return null
    }

    fun finished(position: Vec3, radius: Double): Boolean {
        val end = route.lastOrNull() ?: return false
        if (remaining > radius) return false
        if (distance(position, end) <= radius) return true
        if (route.size < 2) return false
        val previous = route[route.lastIndex - 1]
        return (position.x - end.x) * (end.x - previous.x) +
            (position.z - end.z) * (end.z - previous.z) >= 0.0
    }

    private fun project(position: Vec3, until: Int) {
        var nearest = Double.MAX_VALUE
        var bestLeg = leg
        var bestAlong = along
        for (i in leg until until) {
            val a = route[i]
            val b = route[i + 1]
            val length = distances[i + 1] - distances[i]
            val t = if (length < 1e-6) {
                0.0
            } else {
                (((position.x - a.x) * (b.x - a.x) + (position.z - a.z) * (b.z - a.z)) / (length * length)).coerceIn(0.0, 1.0)
            }
            val candidate = distances[i] + t * length
            if (candidate < along) continue
            val error = distance(position, a.lerp(b, t))
            if (error < nearest) {
                nearest = error
                bestLeg = i
                bestAlong = candidate
            }
        }
        leg = bestLeg
        along = bestAlong
    }

    private fun distance(a: Vec3, b: Vec3): Double = hypot(a.x - b.x, a.z - b.z)

    private companion object {
        const val MIN_LOOK_AHEAD = 6.0
        const val MAX_LOOK_AHEAD = 15.0
        const val SPEED_LOOK_AHEAD = 8.0
        const val TURN_REACHED_RADIUS = 1.0
        const val TURN_COSINE = 0.9
        const val TURN_LEAD = 6.0
    }
}

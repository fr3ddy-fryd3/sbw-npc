package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.SmokeCloudRegistry
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * The smoke on the field, which hides what stands behind it from [DetectionSightline].
 *
 * Reads the [SmokeCloudRegistry], so a sight check costs a short list instead of an entity query.
 * What counts as smoke, and whether it is out yet, is the [Ports.grenades] port's to say.
 */
object SmokeClouds {
    /** How far from a cloud's centre a line of sight is stopped. */
    const val RADIUS = 3.0

    /** Whether smoke lies across the line from [from] to [to]. */
    fun blocks(level: Level, from: Vec3, to: Vec3): Boolean {
        val server = level as? ServerLevel ?: return false
        return SmokeCloudRegistry.all(server).any { Ports.grenades.smokeIsOut(it) && distanceToSegment(from, to, it.position().add(0.0, CLOUD_HEIGHT, 0.0)) <= RADIUS }
    }

    /** Smoke hangs a little above the ground it came to rest on. */
    private const val CLOUD_HEIGHT = 1.0

    /** A segment shorter than this is a point. */
    private const val EMPTY_SEGMENT = 1e-9

    internal fun distanceToSegment(from: Vec3, to: Vec3, point: Vec3): Double {
        val segment = to.subtract(from)
        val length = segment.lengthSqr()
        val along = if (length < EMPTY_SEGMENT) 0.0 else (point.subtract(from).dot(segment) / length).coerceIn(0.0, 1.0)
        return from.add(segment.scale(along)).distanceTo(point)
    }
}

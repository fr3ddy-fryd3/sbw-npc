package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.SmokeCloudRegistry
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.IdentityHashMap

/**
 * The smoke on the field, which hides what stands behind it from [DetectionSightline].
 *
 * Reads the [SmokeCloudRegistry] for grenades still giving smoke, so a sight check costs a short
 * list instead of an entity query, and keeps the clouds of grenades that have gone for as long as
 * their smoke hangs. What counts as smoke, and whether it is out yet, is the [Ports.grenades]
 * port's to say.
 */
object SmokeClouds {
    /** How far from a cloud's centre a line of sight is stopped. */
    const val RADIUS = 3.0

    /**
     * How long a cloud hangs after its grenade is gone. SBW discards an M18 200 ticks after the
     * throw, while its smoke particles live 600 to 800 ticks.
     */
    private const val LINGER_TICKS = 600L

    /** Smoke hangs a little above the ground it came to rest on. */
    private const val CLOUD_HEIGHT = 1.0

    /** A segment shorter than this is a point. */
    private const val EMPTY_SEGMENT = 1e-9

    private class Lingering(val centre: Vec3, val until: Long)

    /** The clouds of grenades that have gone, per level, until their smoke clears. */
    private val lingering = IdentityHashMap<ServerLevel, MutableList<Lingering>>()

    /** Whether smoke lies across the line from [from] to [to]. */
    fun blocks(level: Level, from: Vec3, to: Vec3): Boolean {
        val server = level as? ServerLevel ?: return false
        return SmokeCloudRegistry.all(server).any { Ports.grenades.smokeIsOut(it) && crosses(from, to, it.position()) } ||
            lingering(server).any { crosses(from, to, it.centre) }
    }

    /**
     * Called as [entity] leaves [level]: a smoke grenade that had begun to smoke leaves its cloud
     * behind. Recorded here rather than when someone looks, so the cloud hangs as long from the
     * grenade's end whether or not anyone was looking through it meanwhile.
     */
    fun leave(entity: Entity, level: Level) {
        val server = level as? ServerLevel ?: return
        if (!Ports.grenades.isSmokeCloud(entity) || !Ports.grenades.smokeStarted(entity)) return
        lingering.getOrPut(server, ::ArrayList) += Lingering(entity.position(), server.gameTime + LINGER_TICKS)
    }

    /** Forgets every cloud; the server is stopping. */
    fun clearAll() = lingering.clear()

    private fun lingering(level: ServerLevel): List<Lingering> {
        val clouds = lingering[level] ?: return emptyList()
        val now = level.gameTime
        clouds.removeIf { it.until < now }
        if (clouds.isEmpty()) lingering.remove(level)
        return clouds
    }

    private fun crosses(from: Vec3, to: Vec3, centre: Vec3) = distanceToSegment(from, to, centre.add(0.0, CLOUD_HEIGHT, 0.0)) <= RADIUS

    internal fun distanceToSegment(from: Vec3, to: Vec3, point: Vec3): Double {
        val segment = to.subtract(from)
        val length = segment.lengthSqr()
        val along = if (length < EMPTY_SEGMENT) 0.0 else (point.subtract(from).dot(segment) / length).coerceIn(0.0, 1.0)
        return from.add(segment.scale(along)).distanceTo(point)
    }
}

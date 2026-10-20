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

    /**
     * How long a cloud hangs after the last sight of its grenade. SBW discards an M18 200 ticks
     * after the throw while its smoke particles live 600 to 800 ticks, so the field stayed smoky
     * for half a minute that nobody was hidden behind (in-game 20.10).
     */
    private const val LINGER_TICKS = 600L

    /** Where each grenade's smoke was given out, and when it was last seen giving it, per level. */
    private val clouds = HashMap<ServerLevel, HashMap<java.util.UUID, Pair<Vec3, Long>>>()

    /** Whether smoke lies across the line from [from] to [to]. */
    fun blocks(level: Level, from: Vec3, to: Vec3): Boolean {
        val server = level as? ServerLevel ?: return false
        return out(server).any { distanceToSegment(from, to, it.add(0.0, CLOUD_HEIGHT, 0.0)) <= RADIUS }
    }

    /** The clouds standing in [level] now: those of grenades still giving smoke, and those that have just gone. */
    fun out(level: ServerLevel): List<Vec3> {
        val now = level.gameTime
        val known = clouds.getOrPut(level, ::HashMap)
        SmokeCloudRegistry.all(level).filter(Ports.grenades::smokeIsOut).forEach { known[it.uuid] = it.position() to now }
        known.values.removeIf { (_, seen) -> now - seen > LINGER_TICKS }
        return known.values.map { it.first }
    }

    fun clearAll() = clouds.clear()

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

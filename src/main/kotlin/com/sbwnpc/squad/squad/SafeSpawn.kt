package com.sbwnpc.squad.squad

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.phys.Vec3

/**
 * Squad members get placed in a line (deploy) or scattered around a point (barracks respawn)
 * without any per-member pathfinding or safety check — against uneven terrain (a step, overhang,
 * wall, or just a barracks built into a hillside) that can and did spawn a member with its feet
 * inside a solid block, suffocating it before it ever took a single action. This walks a short
 * vertical column at the candidate X/Z to find a Y with solid footing and clear space for the
 * mob's actual hitbox. Returns null if the column has no safe spot in range at all (e.g. the
 * scattered X/Z landed inside a wall or over a cliff) — callers should try a different X/Z rather
 * than spawn at an unmodified, unverified Y.
 */
object SafeSpawn {
    private const val SEARCH_RANGE = 6
    /** How far out [findClearSpot] is willing to look before giving up. */
    private const val DEFAULT_CLEAR_RADIUS = 16.0
    private const val RING_STEP = 2.0

    fun findSafeY(level: ServerLevel, x: Double, z: Double, aroundY: Int, dimensions: EntityDimensions): Double? {
        if (isSafe(level, x, aroundY.toDouble(), z, dimensions)) return aroundY.toDouble()
        for (offset in 1..SEARCH_RANGE) {
            if (isSafe(level, x, (aroundY + offset).toDouble(), z, dimensions)) return (aroundY + offset).toDouble()
            if (isSafe(level, x, (aroundY - offset).toDouble(), z, dimensions)) return (aroundY - offset).toDouble()
        }
        return null
    }

    /**
     * The nearest spot around [preferredX]/[preferredZ] where something of this size actually fits.
     *
     * [findSafeY] only ever walks a vertical column, which is enough for a soldier but not for a
     * tank or a helicopter: their footprint is wider than the block they are nominally placed on,
     * so one tree, one wall or one hillside makes the whole column unusable and the caller ends up
     * spawning the vehicle inside terrain anyway. This walks outward in rings instead, and returns
     * null rather than a spot that does not fit — nowhere to put it is a real answer the caller
     * has to deal with.
     */
    fun findClearSpot(
        level: ServerLevel,
        preferredX: Double,
        preferredZ: Double,
        aroundY: Int,
        dimensions: EntityDimensions,
        radius: Double = DEFAULT_CLEAR_RADIUS
    ): Vec3? {
        findSafeY(level, preferredX, preferredZ, aroundY, dimensions)
            ?.let { return Vec3(preferredX, it, preferredZ) }

        var ring = RING_STEP
        while (ring <= radius) {
            // More samples the further out, so the spacing between candidates stays roughly even
            // instead of the rings getting sparser as they grow.
            val samples = Math.max(8, Math.round(2 * Math.PI * ring / RING_STEP).toInt())
            for (i in 0 until samples) {
                val angle = 2 * Math.PI * i / samples
                val x = preferredX + Math.cos(angle) * ring
                val z = preferredZ + Math.sin(angle) * ring
                findSafeY(level, x, z, aroundY, dimensions)?.let { return Vec3(x, it, z) }
            }
            ring += RING_STEP
        }
        return null
    }

    private fun isSafe(level: ServerLevel, x: Double, y: Double, z: Double, dimensions: EntityDimensions): Boolean {
        val floor = BlockPos.containing(x, y - 0.1, z)
        if (level.getBlockState(floor).isAir) return false // nothing solid to stand on
        return level.noCollision(dimensions.makeBoundingBox(x, y, z))
    }
}

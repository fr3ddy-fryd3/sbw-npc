package com.sbwnpc.squad.squad

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityDimensions

/**
 * Squad members get placed in a line (deploy) or scattered around a point (barracks respawn)
 * without any per-member pathfinding or safety check — against uneven terrain (a step, overhang,
 * wall, or just a barracks built into a hillside) that can and did spawn a member with its feet
 * inside a solid block, suffocating it before it ever took a single action. This walks a short
 * vertical column at the candidate X/Z to find a Y with solid footing and clear space for the
 * mob's actual hitbox, falling back to the original Y unmodified if nothing safe turns up nearby —
 * spawning imperfectly beats silently dropping the member.
 */
object SafeSpawn {
    private const val SEARCH_RANGE = 6

    fun findSafeY(level: ServerLevel, x: Double, z: Double, aroundY: Int, dimensions: EntityDimensions): Double {
        if (isSafe(level, x, aroundY.toDouble(), z, dimensions)) return aroundY.toDouble()
        for (offset in 1..SEARCH_RANGE) {
            if (isSafe(level, x, (aroundY + offset).toDouble(), z, dimensions)) return (aroundY + offset).toDouble()
            if (isSafe(level, x, (aroundY - offset).toDouble(), z, dimensions)) return (aroundY - offset).toDouble()
        }
        return aroundY.toDouble()
    }

    private fun isSafe(level: ServerLevel, x: Double, y: Double, z: Double, dimensions: EntityDimensions): Boolean {
        val floor = BlockPos.containing(x, y - 0.1, z)
        if (level.getBlockState(floor).isAir) return false // nothing solid to stand on
        return level.noCollision(dimensions.makeBoundingBox(x, y, z))
    }
}

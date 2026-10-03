package com.sbwnpc.squad.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockCollisions
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Use the same entity-aware voxel collisions as movement, including shapes outside their cell. */
internal object WalkingClearance {
    fun leaves(state: BlockState): Boolean = state.block is LeavesBlock || state.`is`(BlockTags.LEAVES)

    fun body(x: Int, y: Double, z: Int, width: Float, height: Float): AABB {
        val half = width / 2.0
        return AABB(x + 0.5 - half, y + 0.001, z + 0.5 - half,
            x + 0.5 + half, y + height - 0.001, z + 0.5 + half)
    }

    fun blockers(level: CollisionGetter, entity: Entity?, box: AABB): List<BlockPos> =
        BlockCollisions(level, entity, box, false) { pos, _ -> pos.immutable() }.asSequence().toList()

    /** Body first, then feet through head in front, then the headroom needed to jump. */
    fun clearingCandidates(level: CollisionGetter, entity: Entity?, body: AABB, direction: Vec3): List<BlockPos> {
        val ahead = body.move(direction.scale(0.8))
        return (blockers(level, entity, body.deflate(0.001)) +
            blockers(level, entity, ahead.deflate(0.001)) +
            blockers(level, entity, ahead.move(0.0, 1.0, 0.0).deflate(0.001))).distinct()
    }

    /** Clear a crown underfoot only when another leaf layer supports a one-block descent. */
    fun lowerCrownLayer(level: CollisionGetter, entity: Entity?, body: AABB): BlockPos? {
        val feet = AABB(body.minX, body.minY - 0.05, body.minZ, body.maxX, body.minY + 0.001, body.maxZ)
        val lower = blockers(level, entity, feet.move(0.0, -1.0, 0.0))
        if (lower.isEmpty() || lower.any { !leaves(level.getBlockState(it)) }) return null
        return blockers(level, entity, feet).firstOrNull { support ->
            leaves(level.getBlockState(support)) &&
                blockers(level, entity, body.move(0.0, -1.0, 0.0).deflate(0.001)).all { it == support }
        }
    }

    /** A recovery descent may spend at most half the current health, leaving at least four HP. */
    fun recoveryFallDistance(health: Float, damage: (Float) -> Int): Int =
        (8 downTo 4).firstOrNull { damage(it + 1f) <= health - maxOf(4f, health / 2f) } ?: 3
}

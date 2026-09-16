package com.sbwnpc.squad.util

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** Shared, bounded terrain probes for orders and AI destination selection. */
object Terrain {
    fun surfaceBelow(level: BlockGetter, loc: Vec3, maxDrop: Int = 200): BlockPos {
        var pos = BlockPos.containing(loc)
        var steps = 0
        while (level.getBlockState(pos).isAir && pos.y > level.minBuildHeight && steps++ < maxDrop) pos = pos.below()
        return pos.above()
    }

    fun lookedAtPos(player: ServerPlayer, level: ServerLevel, reach: Double): BlockPos {
        val hit = player.pick(reach, 1.0f, false)
        if (hit.type == HitResult.Type.BLOCK) return (hit as BlockHitResult).blockPos
        return surfaceBelow(level, hit.location)
    }

    fun standableOrNull(level: BlockGetter, x: Double, y: Double, z: Double, guard: Int = 6): Vec3? {
        var pos = BlockPos.containing(x, y, z)
        var steps = 0
        while (level.getBlockState(pos).isAir && pos.y > level.minBuildHeight && steps++ < guard) pos = pos.below()
        while (!level.getBlockState(pos).isAir && steps++ < guard) pos = pos.above()
        if (level.getBlockState(pos.below()).isAir) return null
        return Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
    }
}

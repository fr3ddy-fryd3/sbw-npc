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

    /** The first air block above the ground at [pos]'s column: down through air, then up through
     *  solid, [guard] steps in all — so on a cliff or in a cave it may stop short of either. */
    fun groundAt(level: BlockGetter, pos: BlockPos, guard: Int = 10): BlockPos {
        var p = pos
        var steps = 0
        while (level.getBlockState(p).isAir && p.y > level.minBuildHeight && steps++ < guard) p = p.below()
        while (!level.getBlockState(p).isAir && steps++ < guard) p = p.above()
        return p
    }

    fun standableOrNull(level: BlockGetter, x: Double, y: Double, z: Double, guard: Int = 6): Vec3? {
        val pos = groundAt(level, BlockPos.containing(x, y, z), guard)
        if (level.getBlockState(pos.below()).isAir) return null
        return Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
    }

    /** Nearest supporting collider at a column; grass and flowers are never a platform. */
    fun feetAt(level: BlockGetter,raw: Vec3,guard: Int=12,clearance: (Vec3)->Boolean = { true }): Vec3? {
        val column=BlockPos.containing(raw)
        for (distance in 0..guard) {
            val offsets=if (distance==0) intArrayOf(0) else intArrayOf(-distance,distance)
            for (offset in offsets) {
                val floor=column.offset(0,offset,0)
                if (floor.y<level.minBuildHeight || floor.y>=level.maxBuildHeight) continue
                val shape=level.getBlockState(floor).getCollisionShape(level,floor)
                val height=shape.toAabbs().filter { it.minX<=0.5 && it.maxX>=0.5 && it.minZ<=0.5 && it.maxZ>=0.5 }
                    .maxOfOrNull { it.maxY } ?: continue
                val feet=Vec3(column.x+0.5,floor.y+height,column.z+0.5)
                if (!level.getFluidState(BlockPos.containing(feet)).isEmpty || !clearance(feet)) continue
                return feet
            }
        }
        return null
    }
}

package com.sbwnpc.squad.combat

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StainedGlassPaneBlock
import net.minecraft.world.level.block.TransparentBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.neoforge.common.Tags

/** Eyes only. Firing lanes and cover searches keep their ordinary collider raycasts. */
object DetectionSightline {
    private const val FOLIAGE_BLOCKS_TO_HIDE = 3

    fun canSeeWithin(observer: Entity,target: Entity,range: Double): Boolean =
        observer.distanceToSqr(target) <= range*range && canSee(observer,target)

    fun canSee(observer: Entity, target: Entity): Boolean =
        observer.level() === target.level() && visible(observer.level(), observer.eyePosition, target.eyePosition, observer)

    fun visible(level: BlockGetter, from: Vec3, to: Vec3, observer: Entity): Boolean =
        visible(level, from, to, CollisionContext.of(observer))

    internal fun visible(level: BlockGetter, from: Vec3, to: Vec3, collision: CollisionContext): Boolean {
        if (level is ServerLevel) TickBudget.chargeRaycast(level)
        return level.clip(DetectionContext(from, to, collision)).type == HitResult.Type.MISS
    }

    private class DetectionContext(from: Vec3, to: Vec3, collision: CollisionContext) :
        ClipContext(from, to, Block.COLLIDER, Fluid.NONE, collision) {
        private var foliage = 0

        override fun getBlockShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape {
            if (isGrass(state)) {
                // Vanilla traverses each voxel once. Count block cells, not samples or plant halves
                // outside the ray; gaps between patches do not restore the sight budget.
                foliage++
                return if (foliage >= FOLIAGE_BLOCKS_TO_HIDE) Shapes.block() else Shapes.empty()
            }
            if (isGlass(state)) return Shapes.empty()
            return super.getBlockShape(state, level, pos)
        }
    }

    private fun isGrass(state: BlockState): Boolean =
        state.`is`(Blocks.SHORT_GRASS) || state.`is`(Blocks.TALL_GRASS) ||
            state.`is`(Blocks.FERN) || state.`is`(Blocks.LARGE_FERN)

    private fun isGlass(state: BlockState): Boolean =
        state.`is`(Tags.Blocks.GLASS_BLOCKS) || state.`is`(Tags.Blocks.GLASS_PANES) ||
            state.block is TransparentBlock || state.block is StainedGlassPaneBlock || state.`is`(Blocks.GLASS_PANE)
}

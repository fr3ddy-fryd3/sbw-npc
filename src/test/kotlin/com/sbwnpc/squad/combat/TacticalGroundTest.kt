package com.sbwnpc.squad.combat

import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalGroundTest {
    private class Ground(val blocks: Map<BlockPos,BlockState>): BlockGetter {
        override fun getBlockState(pos: BlockPos)=blocks[pos] ?: Blocks.AIR.defaultBlockState()
        override fun getFluidState(pos: BlockPos): FluidState=getBlockState(pos).fluidState
        override fun getBlockEntity(pos: BlockPos): BlockEntity?=null
        override fun getHeight()=384
        override fun getMinBuildHeight()=-64
    }

    @Test fun `tall grass does not lift a tactical position two blocks above the ground`() {
        val world=Ground(mapOf(BlockPos(0,63,0) to Blocks.STONE.defaultBlockState(),
            BlockPos(0,64,0) to Blocks.TALL_GRASS.defaultBlockState(),
            BlockPos(0,65,0) to Blocks.TALL_GRASS.defaultBlockState()))
        assertEquals(Vec3(0.5,64.0,0.5),Terrain.feetAt(world,Vec3(0.5,64.0,0.5)))
    }

    @Test fun `slab positions use the actual top of their collision shape`() {
        val world=Ground(mapOf(BlockPos(0,63,0) to Blocks.STONE_SLAB.defaultBlockState()))
        assertEquals(Vec3(0.5,63.5,0.5),Terrain.feetAt(world,Vec3(0.5,64.0,0.5)))
    }

    @Test fun `a supporting floor with no body clearance is rejected`() {
        val world=Ground(mapOf(BlockPos(0,63,0) to Blocks.STONE.defaultBlockState()))
        assertNull(Terrain.feetAt(world,Vec3(0.5,64.0,0.5),2) { false })
    }
}

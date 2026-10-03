package com.sbwnpc.squad.combat

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Exercise vanilla's actual voxel traversal and clipping with independent sight and collider contexts. */
class DetectionSightlineTest {
    private val from = Vec3(0.5, 64.5, 0.5)
    private val to = Vec3(12.5, 64.5, 0.5)
    private val collision = CollisionContext.empty()

    private class Terrain(val blocks: Map<BlockPos, BlockState>) : BlockGetter {
        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos] ?: Blocks.AIR.defaultBlockState()
        override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
        override fun getHeight() = 384
        override fun getMinBuildHeight() = -64
    }

    private fun terrain(block: Block, count: Int): Terrain =
        Terrain((1..count).associate { BlockPos(it, 64, 0) to block.defaultBlockState() })

    private fun seen(world: Terrain, start: Vec3 = from, end: Vec3 = to): Boolean =
        DetectionSightline.visible(world, start, end, collision)

    private fun colliderClear(world: Terrain): Boolean =
        world.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, collision)).type == HitResult.Type.MISS

    @Test
    fun `two foliage blocks can be seen through but the third conceals the target`() {
        for (plant in listOf(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN)) {
            assertTrue(seen(terrain(plant, 2)), "two ${plant.name} blocks should be transparent")
            assertFalse(seen(terrain(plant, 3)), "three ${plant.name} blocks should conceal")
            assertTrue(colliderClear(terrain(plant, 3)), "grass must not become solid for shots or cover")
        }
    }

    @Test
    fun `mixed foliage across separate patches shares one budget`() {
        val plants = listOf(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN)
        val world = Terrain(plants.mapIndexed { i, block -> BlockPos(1 + i * 4, 64, 0) to block.defaultBlockState() }.toMap())
        assertFalse(seen(world))
        assertFalse(seen(world, to, from))
        assertTrue(colliderClear(world))
    }

    @Test
    fun `one plant is counted once however far away the target is`() {
        assertTrue(seen(terrain(Blocks.TALL_GRASS, 1), end = Vec3(72.5, 64.5, 0.5)))
        assertTrue(seen(terrain(Blocks.SHORT_GRASS, 12), Vec3(0.5, 65.6, 0.5), Vec3(12.5, 65.6, 0.5)))
    }

    @Test
    fun `the upper halves of tall grass and large fern conceal at eye height`() {
        for (plant in listOf(Blocks.TALL_GRASS, Blocks.LARGE_FERN)) {
            val upper = plant.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
            val world = Terrain((1..3).associate { BlockPos(it, 65, 0) to upper })
            assertFalse(seen(world, Vec3(0.5, 65.6, 0.5), Vec3(12.5, 65.6, 0.5)))
        }
    }

    @Test
    fun `clear coloured and tinted glass and panes are visible through but still stop firing lanes`() {
        for (glass in listOf(Blocks.GLASS, Blocks.RED_STAINED_GLASS, Blocks.TINTED_GLASS, Blocks.GLASS_PANE, Blocks.RED_STAINED_GLASS_PANE)) {
            val world = terrain(glass, 1)
            assertTrue(seen(world), "${glass.name} should allow detection")
            assertFalse(colliderClear(world), "${glass.name} should keep blocking shots and cover rays")
        }
    }

    @Test
    fun `a wall behind a window still blocks detection`() {
        val world = Terrain(mapOf(
            BlockPos(1, 64, 0) to Blocks.GLASS.defaultBlockState(),
            BlockPos(4, 64, 0) to Blocks.STONE.defaultBlockState()
        ))
        assertFalse(seen(world))
        assertFalse(colliderClear(world))
    }

    @Test
    fun `grass dirt leaves and iron bars retain their solid occlusion`() {
        for (block in listOf(Blocks.GRASS_BLOCK, Blocks.STONE, Blocks.OAK_LEAVES, Blocks.IRON_BARS)) {
            val world = terrain(block, 1)
            assertFalse(seen(world), "${block.name} should remain an obstacle")
            assertFalse(colliderClear(world))
        }
    }

    @Test
    fun `foliage counts are independent for every ray in both directions`() {
        val world = terrain(Blocks.TALL_GRASS, 3)
        assertFalse(seen(world))
        assertFalse(seen(world, to, from))
        assertTrue(seen(world, end = Vec3(2.5, 64.5, 0.5)))
        assertTrue(seen(world, end = Vec3(2.5, 64.5, 0.5)))
    }
}

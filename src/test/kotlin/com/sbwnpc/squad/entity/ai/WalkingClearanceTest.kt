package com.sbwnpc.squad.entity.ai

import com.mojang.serialization.MapCodec
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.border.WorldBorder
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.ceil

/** Actual vanilla collision traversal over partial leaves, roots and oversized branch fixtures. */
class WalkingClearanceTest {
    private class Terrain(private val blocks: Map<BlockPos, BlockState>) : CollisionGetter {
        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos] ?: Blocks.AIR.defaultBlockState()
        override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
        override fun getHeight() = 384
        override fun getMinBuildHeight() = -64
        override fun getWorldBorder() = WorldBorder()
        override fun getChunkForCollisions(chunkX: Int, chunkZ: Int): BlockGetter = this
        override fun getEntityCollisions(entity: Entity?, box: AABB): List<VoxelShape> = emptyList()
    }

    // The test registry is already frozen. Fixture states reuse registered owners without adding
    // synthetic blocks to the game, while vanilla BlockCollisions still traverses their voxels.
    private fun obstacle(shape: VoxelShape, owner: Block = Blocks.STONE): BlockState = object : BlockState(
        owner, Reference2ObjectArrayMap(owner.defaultBlockState().values), MapCodec.unit(owner.defaultBlockState())
    ) {
        override fun getCollisionShape(level: BlockGetter, pos: BlockPos, context: CollisionContext) = shape
        override fun getCollisionShape(level: BlockGetter, pos: BlockPos) = shape
        override fun hasLargeCollisionShape() = true
    }

    // Dynamic Trees 1.7.2 non-vanilla leaf collision: inset sides, bottom half of the block.
    private val leaves = obstacle(Shapes.box(0.125, 0.0, 0.125, 0.875, 0.5, 0.875), Blocks.OAK_LEAVES)
    private val body = WalkingClearance.body(0, 64.0, 0, 0.6f, 1.8f)

    @Test
    fun `half block foliage at the feet is detected even with clear eyes`() {
        val pos = BlockPos(0, 64, 0)
        val world = Terrain(mapOf(pos to leaves))
        assertTrue(world.getBlockState(BlockPos(0, 65, 0)).isAir)
        assertEquals(listOf(pos), WalkingClearance.clearingCandidates(world, null, body, Vec3(1.0, 0.0, 0.0)))
        assertFalse(world.noBlockCollision(null, body))
        assertTrue(WalkingClearance.leaves(leaves))
    }

    @Test
    fun `stacked foliage identifies both body and overhead colliders`() {
        val lower = BlockPos(0, 64, 0)
        val upper = lower.above()
        val world = Terrain(mapOf(lower to leaves, upper to leaves))
        val found = WalkingClearance.clearingCandidates(world, null, body, Vec3.ZERO)
        assertTrue(found.containsAll(listOf(lower, upper)))
    }

    @Test
    fun `partial root blocks ground walking but a raised node stands on its real top`() {
        val root = BlockPos(1, 64, 0)
        val world = Terrain(mapOf(root to obstacle(Shapes.box(0.0, 0.0, 0.25, 1.0, 0.5, 0.75))))
        assertFalse(world.noBlockCollision(null, WalkingClearance.body(1, 64.0, 0, 0.6f, 1.8f)))
        val floor = WalkNodeEvaluator.getFloorLevel(world, root.above())
        assertEquals(64.5, floor)
        assertTrue(world.noBlockCollision(null, WalkingClearance.body(1, floor, 0, 0.6f, 1.8f)))
        assertTrue(WalkingClearance.clearingCandidates(world, null, body, Vec3(1.0, 0.0, 0.0)).contains(root))
    }

    @Test
    fun `a thick branch in the neighboring cell blocks an otherwise empty walking node`() {
        val branch = BlockPos(1, 64, 0)
        // Dynamic Trees' maximum branch radius: 24 pixels, spanning three blocks horizontally.
        val world = Terrain(mapOf(branch to obstacle(Shapes.box(-1.0, 0.0, -1.0, 2.0, 1.0, 2.0))))
        assertTrue(world.getBlockState(BlockPos(0, 64, 0)).isAir)
        assertFalse(world.noBlockCollision(null, body))
        assertEquals(listOf(branch), WalkingClearance.blockers(world, null, body))
    }

    @Test
    fun `jumping headroom is checked while the supporting ground stays intact`() {
        val overhead = BlockPos(1, 66, 0)
        val world = Terrain(mapOf(
            overhead to Blocks.OAK_LEAVES.defaultBlockState(),
            BlockPos(0, 63, 0) to Blocks.DIRT.defaultBlockState(),
            BlockPos(1, 63, 0) to Blocks.DIRT.defaultBlockState()
        ))
        assertTrue(world.noBlockCollision(null, body))
        assertEquals(listOf(overhead), WalkingClearance.clearingCandidates(world, null, body, Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun `foliage support does not collide with a body standing above its half height`() {
        val world = Terrain(mapOf(BlockPos(0, 63, 0) to leaves))
        val floor = WalkNodeEvaluator.getFloorLevel(world, BlockPos(0, 64, 0))
        assertEquals(63.5, floor)
        assertTrue(world.noBlockCollision(null, WalkingClearance.body(0, floor, 0, 0.6f, 1.8f)))
    }

    @Test
    fun `a stranded NPC can lower itself one layer into a thick crown`() {
        val top = BlockPos(0, 64, 0)
        val world = Terrain(mapOf(top to leaves, top.below() to leaves))
        val standing = WalkingClearance.body(0, 64.5, 0, 0.6f, 1.8f)
        assertEquals(top, WalkingClearance.lowerCrownLayer(world, null, standing))
    }

    @Test
    fun `clearing the leaf underfoot must not drop an NPC through an empty shaft`() {
        val top = BlockPos(0, 64, 0)
        val world = Terrain(mapOf(top to leaves, top.below(10) to Blocks.STONE.defaultBlockState()))
        val standing = WalkingClearance.body(0, 64.5, 0, 0.6f, 1.8f)
        assertNull(WalkingClearance.lowerCrownLayer(world, null, standing))
    }

    private fun normalFallDamage(distance: Float) = maxOf(0, ceil(distance - 3f).toInt())

    @Test
    fun `recovery descent is bounded and keeps a health reserve`() {
        assertEquals(8, WalkingClearance.recoveryFallDistance(20f, ::normalFallDamage))
        assertEquals(7, WalkingClearance.recoveryFallDistance(10f, ::normalFallDamage))
        assertEquals(4, WalkingClearance.recoveryFallDistance(6f, ::normalFallDamage))
        assertEquals(3, WalkingClearance.recoveryFallDistance(4f, ::normalFallDamage))
        assertEquals(3, WalkingClearance.recoveryFallDistance(1f, ::normalFallDamage))
    }

    @Test
    fun `modified fall damage is respected rather than assuming one damage per block`() {
        assertEquals(5, WalkingClearance.recoveryFallDistance(20f) { normalFallDamage(it) * 3 })
        assertEquals(8, WalkingClearance.recoveryFallDistance(20f) { 0 })
    }
}

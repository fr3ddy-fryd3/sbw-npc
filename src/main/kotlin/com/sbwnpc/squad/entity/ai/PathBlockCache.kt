package com.sbwnpc.squad.entity.ai

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockCollisions
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.VoxelShape

/** Block reads shared by overlapping node bodies during one synchronous path search only. */
internal class PathBlockCache(private val source: CollisionGetter) : CollisionGetter by source {
    private val tiles = Long2ObjectOpenHashMap<Array<BlockState?>>()
    private var lastTileKey = Long.MIN_VALUE
    private var lastTile: Array<BlockState?>? = null
    private val chunks = Long2ObjectOpenHashMap<BlockGetter>()

    override fun getBlockState(pos: BlockPos): BlockState = state(source, pos)

    private fun state(reader: BlockGetter, pos: BlockPos): BlockState {
        // A collider walks adjacent cells. Direct indexing avoids hashing every cached read;
        // eight-block tiles stay sparse without allocating the complete search volume.
        val key = BlockPos.asLong(pos.x shr TILE_SHIFT, pos.y shr TILE_SHIFT, pos.z shr TILE_SHIFT)
        val tile = if (key == lastTileKey) {
            lastTile!!
        } else {
            (tiles.get(key) ?: arrayOfNulls<BlockState>(TILE_SIZE).also { tiles.put(key, it) }).also {
                lastTileKey = key
                lastTile = it
            }
        }
        val index = ((pos.y and TILE_MASK) shl (TILE_SHIFT * 2)) or ((pos.z and TILE_MASK) shl TILE_SHIFT) or (pos.x and TILE_MASK)
        tile[index]?.let { return it }
        return reader.getBlockState(pos).also { tile[index] = it }
    }

    override fun getChunkForCollisions(chunkX: Int, chunkZ: Int): BlockGetter? {
        val key = ChunkPos.asLong(chunkX, chunkZ)
        chunks.get(key)?.let { return it }
        val chunk = source.getChunkForCollisions(chunkX, chunkZ) ?: return null
        return object : BlockGetter by chunk {
            override fun getBlockState(pos: BlockPos): BlockState = state(chunk, pos)
        }.also { chunks.put(key, it) }
    }

    // Kotlin delegation also delegates Java default methods. Run those on this getter so
    // BlockCollisions reads the cached chunks; preserve the vanilla voxel/context checks.
    override fun getBlockCollisions(entity: Entity?, box: AABB): Iterable<VoxelShape> = Iterable { BlockCollisions(this, entity, box, false) { _, shape -> shape } }

    override fun noBlockCollision(entity: Entity?, box: AABB): Boolean = super<CollisionGetter>.noBlockCollision(entity, box)

    override fun noCollision(entity: Entity?, box: AABB): Boolean = super<CollisionGetter>.noCollision(entity, box)

    private companion object {
        const val TILE_SHIFT = 3
        const val TILE_MASK = (1 shl TILE_SHIFT) - 1
        const val TILE_SIZE = 1 shl (TILE_SHIFT * 3)
    }
}

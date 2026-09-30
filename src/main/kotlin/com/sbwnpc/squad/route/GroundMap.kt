package com.sbwnpc.squad.route

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The lie of the land as the long-route planner ([CellPlanner]) sees it, kept after the ground
 * unloads — the same memory [com.sbwnpc.squad.vehicle.WaterMap] gives the boats, for everything
 * that goes over ground.
 *
 * Per column: the height a man stands at on its surface, and what that surface is — ground, water
 * (the height is the water's surface), a tree trunk, ground with no room over it to stand (a low
 * canopy), or ground that hurts (lava, cactus, magma). The surface is the highest block that stops
 * movement leaving out leaves, then down past any trunk: a tree top is not somewhere to walk.
 *
 * A loaded chunk is read again after [REFRESH_TICKS]; one that isn't is used as last seen for
 * [FORGET_TICKS]; one nobody has seen is unknown.
 */
object GroundMap {
    enum class Kind { UNKNOWN, GROUND, WATER, TREE, NO_ROOM, DANGER }

    private class Chunk(val height: ShortArray, val kind: ByteArray, val room: ByteArray, val seenAt: Long)

    private const val REFRESH_TICKS = 600L
    private const val FORGET_TICKS = 36_000L
    private const val MAX_CHUNKS = 40_000
    /** Free blocks over the ground counted up to. */
    const val MAX_ROOM = 4
    /** Blocks down past trunks at most, to the ground a tree stands on. */
    private const val MAX_TRUNK = 40
    /** Blocks of snow or the like lying on the ground counted as floor at most. */
    private const val MAX_LYING = 2

    private data class Key(val dimension: String, val cx: Int, val cz: Int)

    private val chunks = HashMap<Key, Chunk>()
    private val kinds = Kind.entries

    fun clearAll() = chunks.clear()

    fun known(level: ServerLevel, x: Int, z: Int): Boolean = chunkAt(level, x shr 4, z shr 4) != null

    fun kind(level: ServerLevel, x: Int, z: Int): Kind {
        val chunk = chunkAt(level, x shr 4, z shr 4) ?: return Kind.UNKNOWN
        return kinds[chunk.kind[(x and 15) * 16 + (z and 15)].toInt()]
    }

    /** Free blocks over the ground at column ([x], [z]), up to [MAX_ROOM] — a tank needs more than a man. */
    fun room(level: ServerLevel, x: Int, z: Int): Int {
        val chunk = chunkAt(level, x shr 4, z shr 4) ?: return 0
        return chunk.room[(x and 15) * 16 + (z and 15)].toInt()
    }

    /** The height a man stands at over column ([x], [z]) — on the ground, or at the water's surface. */
    fun height(level: ServerLevel, x: Int, z: Int): Int {
        val chunk = chunkAt(level, x shr 4, z shr 4) ?: return Int.MIN_VALUE
        return chunk.height[(x and 15) * 16 + (z and 15)].toInt()
    }

    private fun chunkAt(level: ServerLevel, cx: Int, cz: Int): Chunk? {
        val key = Key(level.dimension().location().toString(), cx, cz)
        val now = level.gameTime
        val kept = chunks[key]
        if (level.chunkSource.getChunkNow(cx, cz) != null) {
            if (kept != null && now - kept.seenAt < REFRESH_TICKS) return kept
            return read(level, cx, cz, now).also { remember(key, it, now) }
        }
        return kept?.takeIf { now - it.seenAt < FORGET_TICKS }
    }

    private fun remember(key: Key, chunk: Chunk, now: Long) {
        if (chunks.size >= MAX_CHUNKS) chunks.entries.removeIf { now - it.value.seenAt > FORGET_TICKS / 4 }
        chunks[key] = chunk
    }

    private fun read(level: ServerLevel, cx: Int, cz: Int, now: Long): Chunk {
        val chunk = level.chunkSource.getChunkNow(cx, cz)!!
        val height = ShortArray(256)
        val kind = ByteArray(256)
        val room = ByteArray(256)
        val cursor = BlockPos.MutableBlockPos()
        for (lx in 0 until 16) for (lz in 0 until 16) {
            val x = (cx shl 4) + lx
            val z = (cz shl 4) + lz
            val i = lx * 16 + lz
            var y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz)
            var trunk = false
            var steps = 0
            while (steps++ < MAX_TRUNK && level.getBlockState(cursor.set(x, y, z)).`is`(BlockTags.LOGS)) {
                trunk = true
                y--
            }
            val state = level.getBlockState(cursor.set(x, y, z))
            val fluid = level.getFluidState(cursor)
            val k = when {
                fluid.`is`(FluidTags.WATER) -> Kind.WATER
                fluid.`is`(FluidTags.LAVA) -> Kind.DANGER
                trunk -> Kind.TREE
                state.`is`(Blocks.CACTUS) || state.`is`(Blocks.MAGMA_BLOCK) || state.`is`(Blocks.CAMPFIRE) ||
                    state.`is`(Blocks.SWEET_BERRY_BUSH) || state.`is`(Blocks.POWDER_SNOW) -> Kind.DANGER
                else -> null
            }
            if (k != null) {
                kind[i] = k.ordinal.toByte()
                height[i] = (if (k == Kind.WATER) y else y + 1).toShort()
                continue
            }
            // Whatever lies on the ground without blocking movement — snow, a carpet — is the floor
            // a man stands on, not something over his head: counted as that, a snowfield read as
            // ground with no room over it, and no way on foot was found across a snowy mountain.
            var floor = y
            var surface = y + topOf(level, cursor.set(x, y, z))
            while (floor - y < MAX_LYING) {
                cursor.set(x, floor + 1, z)
                val lying = level.getBlockState(cursor)
                val shape = lying.getCollisionShape(level, cursor)
                if (shape.isEmpty || lying.blocksMotion()) break
                floor++
                surface = floor + shape.max(Direction.Axis.Y)
            }
            room[i] = roomOver(level, cursor, x, floor, z).toByte()
            kind[i] = (if (room[i] < 2) Kind.NO_ROOM else Kind.GROUND).ordinal.toByte()
            height[i] = Math.floor(surface + 0.5).toInt().toShort()
        }
        return Chunk(height, kind, room, now)
    }

    /** Where on the block at [pos] a man stands: the top of its shape, a whole block if it has none. */
    private fun topOf(level: ServerLevel, pos: BlockPos): Double {
        val shape = level.getBlockState(pos).getCollisionShape(level, pos)
        return if (shape.isEmpty) 1.0 else shape.max(Direction.Axis.Y)
    }

    /** Blocks free over the ground at [y], up to [MAX_ROOM] — a low branch or overhang cuts it. */
    private fun roomOver(level: ServerLevel, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Int {
        for (dy in 1..MAX_ROOM) {
            cursor.set(x, y + dy, z)
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty) return dy - 1
        }
        return MAX_ROOM
    }
}

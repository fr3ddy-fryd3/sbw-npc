package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import java.util.BitSet

/**
 * What the boats' planner has seen of the water, kept after the ground unloads.
 *
 * The planner ([WaterRoutes]) only sees ground that's loaded, and the ground loads round players
 * and busy squads, not round the goal. Without a memory it rediscovered the world every time it
 * looked: a boat sent up a bay toward the goal found it closed, turned back — and the far side of
 * the bay, unloaded again by then, looked like open water once more, so it went back up it, and
 * on for ever. Kept here, every chunk a search has looked at stays known, as the usual approach to
 * planning in a world seen bit by bit has it: only what nobody has seen yet is unknown.
 *
 * Per chunk and water surface height: which columns are open water with room above for a hull,
 * and which are ground a man can step out onto one or two blocks above the water. A loaded chunk is
 * read again after [REFRESH_TICKS]; one that isn't is used as last seen for [FORGET_TICKS].
 */
object WaterMap {
    enum class Kind { UNKNOWN, WATER, SHORE_LOW, SHORE_HIGH, OTHER }

    private class Chunk(val water: BitSet, val shoreLow: BitSet, val shoreHigh: BitSet, val seenAt: Long)

    private const val REFRESH_TICKS = 600L
    private const val FORGET_TICKS = 36_000L
    private const val MAX_CHUNKS = 40_000

    private val chunks = HashMap<Key, Chunk>()

    private data class Key(val dimension: String, val y: Int, val cx: Int, val cz: Int)

    fun clearAll() = chunks.clear()

    /** What column ([x], [z]) is at water surface height [y], as seen now or last seen. */
    fun kind(level: ServerLevel, y: Int, x: Int, z: Int): Kind {
        val chunk = chunkAt(level, y, x shr 4, z shr 4) ?: return Kind.UNKNOWN
        val i = (x and 15) * 16 + (z and 15)
        return when {
            chunk.water[i] -> Kind.WATER
            chunk.shoreLow[i] -> Kind.SHORE_LOW
            chunk.shoreHigh[i] -> Kind.SHORE_HIGH
            else -> Kind.OTHER
        }
    }

    /** Whether anything is known of the ground at ([x], [z]). */
    fun known(level: ServerLevel, y: Int, x: Int, z: Int): Boolean = chunkAt(level, y, x shr 4, z shr 4) != null

    private fun chunkAt(level: ServerLevel, y: Int, cx: Int, cz: Int): Chunk? {
        val key = Key(level.dimension().location().toString(), y, cx, cz)
        val now = level.gameTime
        val kept = chunks[key]
        if (level.chunkSource.getChunkNow(cx, cz) != null) {
            if (kept != null && now - kept.seenAt < REFRESH_TICKS) return kept
            return read(level, y, cx, cz, now).also { remember(key, it, now) }
        }
        return kept?.takeIf { now - it.seenAt < FORGET_TICKS }
    }

    private fun remember(key: Key, chunk: Chunk, now: Long) {
        if (chunks.size >= MAX_CHUNKS) chunks.entries.removeIf { now - it.value.seenAt > FORGET_TICKS / 4 }
        chunks[key] = chunk
    }

    private fun read(level: ServerLevel, y: Int, cx: Int, cz: Int, now: Long): Chunk {
        val water = BitSet(256)
        val shoreLow = BitSet(256)
        val shoreHigh = BitSet(256)
        val cursor = BlockPos.MutableBlockPos()
        for (lx in 0 until 16) for (lz in 0 until 16) {
            val x = (cx shl 4) + lx
            val z = (cz shl 4) + lz
            val i = lx * 16 + lz
            if (level.getFluidState(cursor.set(x, y, z)).`is`(FluidTags.WATER)) {
                var room = true
                for (dy in 1..2) {
                    cursor.set(x, y + dy, z)
                    if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty) room = false
                }
                if (room) water.set(i)
                continue
            }
            // Ground a man steps out onto from a boat: its surface one or two blocks over the water.
            val spot = Terrain.standableOrNull(level, x + 0.5, y + 2.0, z + 0.5, 4) ?: continue
            if (level.getFluidState(BlockPos.containing(spot.x, spot.y - 1.0, spot.z)).`is`(FluidTags.WATER)) continue
            when (spot.y.toInt()) {
                y + 1 -> shoreLow.set(i)
                y + 2 -> shoreHigh.set(i)
            }
        }
        return Chunk(water, shoreLow, shoreHigh, now)
    }
}

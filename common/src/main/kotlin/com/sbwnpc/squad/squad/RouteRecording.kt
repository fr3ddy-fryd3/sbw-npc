package com.sbwnpc.squad.squad

import net.minecraft.core.BlockPos
import java.util.UUID

/** Transient, server-side "is this player currently recording a patrol route" state — tied to the
 *  player, not the item, so switching what's in hand mid-recording doesn't lose the buffer. */
object RouteRecording {
    private val buffers = HashMap<UUID, MutableList<BlockPos>>()

    fun isRecording(player: UUID): Boolean = buffers.containsKey(player)
    fun pointCount(player: UUID): Int = buffers[player]?.size ?: 0

    fun start(player: UUID) {
        buffers[player] = mutableListOf()
    }

    fun addPoint(player: UUID, pos: BlockPos): Int? {
        val buf = buffers[player] ?: return null
        buf.add(pos)
        return buf.size
    }

    /** Consumes and returns the recorded points, or null if not recording. */
    fun finish(player: UUID): List<BlockPos>? = buffers.remove(player)

    fun cancel(player: UUID) {
        buffers.remove(player)
    }

    fun clearAll() {
        buffers.clear()
    }
}

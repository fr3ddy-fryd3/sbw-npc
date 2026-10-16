package com.sbwnpc.squad.entity.ai

import net.minecraft.core.BlockPos

/** Short-lived, directed failures of physical steps; other approaches to the same block stay usable. */
class FailedWalkingSteps {
    private data class Edge(val from: BlockPos, val to: BlockPos)
    private val until = LinkedHashMap<Edge, Long>()

    fun reject(from: BlockPos, to: BlockPos, now: Long) {
        until.entries.removeIf { it.value <= now }
        if (until.size >= 32) until.remove(until.keys.first())
        until[Edge(from.immutable(), to.immutable())] = now + 200
    }

    fun permits(from: BlockPos, to: BlockPos, now: Long): Boolean = now >= (until[Edge(from, to)] ?: Long.MIN_VALUE)
}

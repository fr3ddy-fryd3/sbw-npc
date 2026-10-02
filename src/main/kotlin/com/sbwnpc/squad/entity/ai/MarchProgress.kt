package com.sbwnpc.squad.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Route continuity and each walker's forward progress, independent of search scheduling. */
internal class MarchProgress(private val maxNodes: Int) {
    var route: List<BlockPos> = emptyList()
        private set
    private val cursors = HashMap<UUID, Int>()

    fun contains(walker: UUID): Boolean = walker in cursors

    fun index(walker: UUID, position: Vec3, remember: Boolean = true): Int {
        if (route.isEmpty()) return 0
        val previous = cursors[walker]
        val from = previous ?: 0
        // Nearby folds in a trail must not switch a walker to a different leg of the route.
        val until = if (previous == null) route.size else minOf(route.size, from + 33)
        val index = nearest(position, from, until)
        if (remember) cursors[walker] = index
        return index
    }

    fun append(extension: List<BlockPos>) {
        if (extension.isEmpty()) return
        // A fallback search starts at a walker, possibly before the old end. Keep the part
        // behind that junction and replace its unfinished tail, rather than append a backtrack.
        val join = when {
            route.isEmpty() -> -1
            route.last() == extension.first() -> route.lastIndex
            else -> nearest(Vec3.atCenterOf(extension.first()), 0, route.size)
        }
        val prefix = route.take(join + 1)
        val tail = if (prefix.lastOrNull() == extension.first()) extension.drop(1) else extension
        val combined = prefix + tail
        val dropped = (combined.size - maxNodes).coerceAtLeast(0)
        route = combined.drop(dropped)
        cursors.replaceAll { _, at -> (minOf(at, join.coerceAtLeast(0)) - dropped).coerceAtLeast(0) }
    }

    private fun nearest(position: Vec3, from: Int, until: Int): Int {
        var best = from
        var distance = Double.MAX_VALUE
        for (i in from until until) {
            val next = route[i].distToCenterSqr(position)
            // Keep the earlier leg on a tie, especially where a route crosses itself.
            if (next < distance) {
                best = i
                distance = next
            }
        }
        return best
    }
}

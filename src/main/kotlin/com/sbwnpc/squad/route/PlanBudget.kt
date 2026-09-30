package com.sbwnpc.squad.route

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Runs the long-route searches — boats, marches, vehicles — from the server tick, a few thousand
 * units of work a tick shared out among all of them in turn.
 *
 * They used to run only when whoever wanted one asked after it, and a man asks for his path once a
 * second: a search worth a tick and a half took twenty seconds, the first to ask each tick took the
 * whole share, and the squad stood waiting for its route while the rest of the server was idle.
 */
object PlanBudget {
    /** Units of [CellPlanner.Search] work per server tick, all searches together — a few milliseconds. */
    private const val PER_TICK = 2_500
    /** No search gets less than this in a tick it is run, or a crowd of them would each crawl. */
    private const val MIN_SHARE = 250
    /** A search nobody has asked after in this long is dropped. */
    private const val ABANDON_TICKS = 200

    private val running = LinkedHashMap<CellPlanner.Search, Int>()

    fun clearAll() = running.clear()

    /** Hands [search] on to be run from the server tick; true once it's over. */
    fun advance(level: ServerLevel, search: CellPlanner.Search): Boolean {
        if (search.finished) {
            running.remove(search)
            return true
        }
        running[search] = level.server.tickCount
        return false
    }

    fun tick(server: MinecraftServer) {
        if (running.isEmpty()) return
        val now = server.tickCount
        running.entries.removeIf { it.key.finished || now - it.value > ABANDON_TICKS }
        if (running.isEmpty()) return
        val share = maxOf(MIN_SHARE, PER_TICK / running.size)
        var left = PER_TICK
        // Whoever ran first last tick goes to the back, so a crowd of searches all get their turn.
        val order = running.keys.toList()
        for (search in order) {
            if (left <= 0) break
            val before = search.expanded
            search.step(minOf(share, left))
            left -= (search.expanded - before).coerceAtLeast(1)
            running.remove(search)?.let { running[search] = it }
        }
    }
}

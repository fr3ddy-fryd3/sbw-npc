package com.sbwnpc.squad.route

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * Runs the long-route searches — boats, marches, vehicles — from the server tick, a few thousand
 * units of work a tick, oldest search first.
 *
 * They used to run only when whoever wanted one asked after it, and a man asks for his path once a
 * second: a search worth a tick and a half took twenty seconds, the first to ask each tick took the
 * whole share, and the squad stood waiting for its route while the rest of the server was idle.
 *
 * Oldest first, not an even share: twenty searches for six squads ordered off together, shared out
 * evenly, all came back together after 25 s, and every squad stood until then (#145). In turn, the
 * first squad is off within a second or two and only the last waits as long as the work takes.
 */
object PlanBudget {
    /** Units of [CellPlanner.Search] work per server tick, all searches together — a few milliseconds. */
    private const val PER_TICK = 2_500

    /** A search nobody has asked after in this long is dropped. */
    private const val ABANDON_TICKS = 200

    /**
     * A search nobody has asked after in this long waits behind the rest: whoever wanted it may have
     * been given another order, and at the head of the queue it would take every tick's work until
     * dropped. Over the second a man waits between asking.
     */
    private const val STALE_TICKS = 40

    private val running = LinkedHashMap<CellPlanner.Search, Int>()

    fun clearAll() = running.clear()

    /** Hands [search] on to be run from the server tick; true once it's over. */
    fun advance(level: ServerLevel, search: CellPlanner.Search): Boolean = advance(search, level.server.tickCount)

    internal fun advance(search: CellPlanner.Search, now: Int): Boolean {
        if (search.finished) {
            running.remove(search)
            return true
        }
        running[search] = now
        return false
    }

    fun tick(server: MinecraftServer) = tick(server.tickCount)

    internal fun tick(now: Int) {
        if (running.isEmpty()) return
        running.entries.removeIf { it.key.finished || now - it.value > ABANDON_TICKS }
        if (running.isEmpty()) return
        var left = PER_TICK
        // Insertion order is the order they were started in; asking again doesn't move a search.
        val (asked, stale) = running.entries.partition { now - it.value <= STALE_TICKS }
        for (search in (asked + stale).map { it.key }) {
            if (left <= 0) break
            val before = search.expanded
            search.step(left)
            left -= (search.expanded - before).coerceAtLeast(1)
        }
    }
}

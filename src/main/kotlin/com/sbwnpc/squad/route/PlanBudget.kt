package com.sbwnpc.squad.route

import net.minecraft.server.level.ServerLevel

/** The long-route searches' share of a server tick, all of them together: boats and marches. */
object PlanBudget {
    /** Units of [CellPlanner.Search] work per server tick — a few milliseconds. */
    private const val PER_TICK = 2_500

    private var tick = Long.MIN_VALUE
    private var left = 0

    fun clearAll() {
        tick = Long.MIN_VALUE
    }

    /** Runs [search] on with what's left of this tick's share; true once it's over. */
    fun advance(level: ServerLevel, search: CellPlanner.Search): Boolean {
        if (tick != level.gameTime) {
            tick = level.gameTime
            left = PER_TICK
        }
        if (left <= 0) return false
        val before = search.expanded
        val done = search.step(left)
        left -= (search.expanded - before).coerceAtLeast(1)
        return done
    }
}

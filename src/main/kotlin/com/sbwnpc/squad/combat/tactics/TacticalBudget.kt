package com.sbwnpc.squad.combat.tactics

/** Path searches are synchronous. Deferral is distinct from an unreachable destination. */
object TacticalBudget {
    private var tick = Long.MIN_VALUE
    private var paths = 0
    fun path(now: Long): Boolean {
        if (tick != now) { tick = now; paths = 0 }
        if (paths >= 4) return false
        paths++
        return true
    }
}

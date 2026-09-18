package com.sbwnpc.squad.combat

/** Bound catch-up after a server stall: discard old shot debt instead of extending the stall. */
internal object ShotBudget {
    const val MAX_SHOTS_PER_TICK = 8

    inline fun fire(elapsedMs: Long, intervalMs: Long, shoot: () -> Unit): Long {
        require(intervalMs > 0)
        val elapsed = elapsedMs.coerceAtLeast(0)
        val shots = (elapsed / intervalMs).coerceAtMost(MAX_SHOTS_PER_TICK.toLong()).toInt()
        repeat(shots) { shoot() }
        return elapsed % intervalMs
    }
}

package com.sbwnpc.squad.combat

import net.minecraft.server.level.ServerLevel

/**
 * Global per-server-tick budget for the expensive terrain raycasts ([Sightline.blocked] →
 * `Level.clip`) that the position searches spend — `GunAttackBehaviour.bestFiringSpot` and
 * `SeekCoverBehaviour.findCover` each burn hundreds per evaluation, and both are triggered for a
 * whole squad on the SAME tick (everyone enters shoot range together; one mortar shell suppresses
 * everyone in its radius together). Without a cap that's a visible freeze; with it, the searches
 * simply spread themselves across the next few ticks (each is written as a resumable scan, see the
 * callers) and total work per tick stays bounded no matter how many NPCs are on the map.
 *
 * Counts only what callers explicitly charge — [Sightline.blocked] charges itself, block-state
 * reads are not counted (cheap by comparison). Single-threaded: the server tick is.
 */
object TickBudget {
    /** ~5-10 µs per clip on typical terrain → this caps raycast time at a few ms per tick. */
    private const val RAYCASTS_PER_TICK = 500

    private var tick = Long.MIN_VALUE
    private var used = 0

    private fun roll(level: ServerLevel) {
        val now = level.gameTime
        if (now != tick) {
            tick = now
            used = 0
        }
    }

    /** True while this tick can still afford more raycasts — searches check this before each
     *  candidate and suspend (resume next tick) once it's false. */
    fun hasRaycasts(level: ServerLevel): Boolean {
        roll(level)
        return used < RAYCASTS_PER_TICK
    }

    fun chargeRaycast(level: ServerLevel) {
        roll(level)
        used++
    }
}

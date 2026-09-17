package com.sbwnpc.squad.combat

import com.sbwnpc.squad.SquadMod

/**
 * Central switch for in-game debug particle markers (cover-search choice in
 * [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour], firing-position choice in
 * [com.sbwnpc.squad.entity.ai.GunAttackBehaviour]) — per user request: these get toggled off
 * temporarily fairly often (e.g. playing with a friend, not wanting the particle spam), so flip
 * [MARKERS_ENABLED] here rather than commenting out individual `sendParticles` call sites scattered
 * across files.
 *
 * [LOGGING_ENABLED] gates the `[vehicle-debug]` / `[dig-debug]` trace lines the same way (per user
 * decision: keep the messages, gate them — not delete them). Both default OFF: with many NPCs the
 * per-NPC trace lines alone were tens of synchronous log writes per second on the server thread,
 * and the marker particles are a network broadcast to every player per pick. Use [log] rather than
 * calling the logger directly so the message (and its `Vec3`/UUID formatting) is never even built
 * while the flag is off.
 */
object DebugFlags {
    var MARKERS_ENABLED = false
    var LOGGING_ENABLED = false

    /** Same `{}`-placeholder contract as `Logger.info(format, args...)` — call sites keep their
     *  messages verbatim; only the formatting + write is skipped while the flag is off. */
    fun log(format: String, vararg args: Any?) {
        if (LOGGING_ENABLED) SquadMod.LOGGER.info(format, *args)
    }
}

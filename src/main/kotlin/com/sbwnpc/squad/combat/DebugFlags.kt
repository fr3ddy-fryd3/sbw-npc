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
 * decision: keep the messages, gate them — not delete them). With many NPCs the per-NPC trace
 * lines alone were tens of synchronous log writes per second on the server thread, and the marker
 * particles are a network broadcast to every player per pick. Use [log] rather than calling the
 * logger directly so the message (and its `Vec3`/UUID formatting) is never even built while the
 * flag is off.
 *
 * Both default to [BuildFlags.DEBUG_ENABLED], baked in at build time by the `generateBuildFlags`
 * Gradle task — off for a plain `build`/`buildProduction`, on for `buildDevelop`. Still plain
 * `var`s on top of that: a debug command or a debugger can flip them at runtime regardless of
 * which jar you're running.
 */
object DebugFlags {
    var MARKERS_ENABLED = BuildFlags.DEBUG_ENABLED
    var LOGGING_ENABLED = BuildFlags.DEBUG_ENABLED

    /** Same `{}`-placeholder contract as `Logger.info(format, args...)` — call sites keep their
     *  messages verbatim; only the formatting + write is skipped while the flag is off. */
    fun log(format: String, vararg args: Any?) {
        if (LOGGING_ENABLED) SquadMod.LOGGER.info(format, *args)
    }
}

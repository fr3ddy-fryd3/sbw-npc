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
 * Trace lines come in [LogGroup]s, each written only while it is listed in
 * `config/sbwnpc-debug.toml` ([com.sbwnpc.squad.config.DebugConfig]) — none by default, so a test
 * turns on just what it is about instead of the whole log at once. With many NPCs the per-NPC
 * trace lines alone were tens of synchronous log writes per second on the server thread. Use
 * [log] rather than calling the logger directly so the message (and its `Vec3`/UUID formatting)
 * is never even built while its group is off; guard anything costly to gather with [on].
 *
 * The markers default to [BuildFlags.DEBUG_ENABLED], baked in at build time by the
 * `generateBuildFlags` Gradle task — off for a plain `build`/`buildProduction`, on for
 * `buildDevelop`; a release build writes no trace lines at all.
 */
object DebugFlags {
    var MARKERS_ENABLED = BuildFlags.DEBUG_ENABLED
    /** Set from the debug config as it loads and whenever the file is saved. */
    @Volatile
    var enabledGroups: Set<LogGroup> = emptySet()
    /** The map feed shows every faction's squads, NPCs and vehicles, not just the player's side
     *  and the enemies it has spotted — for testing, where one player fields every side. */
    var MAP_SHOWS_ALL = BuildFlags.DEBUG_ENABLED

    fun on(group: LogGroup): Boolean = BuildFlags.DEBUG_ENABLED && group in enabledGroups

    /** Same `{}`-placeholder contract as `Logger.info(format, args...)`, written as `[group-debug] ...`
     *  — the formatting and the write are skipped while [group] is off. */
    fun log(group: LogGroup, format: String, vararg args: Any?) {
        if (on(group)) SquadMod.LOGGER.info(group.tag + format, *args)
    }
}

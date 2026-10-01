package com.sbwnpc.squad.config

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Which trace-log groups a debug build writes (`config/sbwnpc-debug.toml`). Registered only in a
 * `buildDevelop` jar, so a release build has no such file. NeoForge re-reads the file when it is
 * saved, so groups can be switched while the game runs.
 */
object DebugConfig {
    const val FILE = "sbwnpc-debug.toml"

    private val builder = ModConfigSpec.Builder()

    val LOG_GROUPS: ModConfigSpec.ConfigValue<List<String>> = builder
        .comment(
            "Trace-log groups to write to the game log, e.g. [\"path\", \"hearing\"]; \"all\" for every one.",
            "Groups: ${LogGroup.entries.joinToString { it.key }}",
            "Takes effect as soon as the file is saved."
        )
        .defineListAllowEmpty("logGroups", { emptyList<String>() }, { "" }, { it is String })

    val SPEC: ModConfigSpec = builder.build()

    fun onConfig(event: ModConfigEvent) {
        if (event.config.spec !== SPEC || event is ModConfigEvent.Unloading) return
        val names = LOG_GROUPS.get()
        DebugFlags.enabledGroups = if ("all" in names) LogGroup.entries.toSet()
            else names.mapNotNull(LogGroup::byKey).toSet()
    }
}

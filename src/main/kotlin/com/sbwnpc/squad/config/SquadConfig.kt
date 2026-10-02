package com.sbwnpc.squad.config

import net.neoforged.neoforge.common.ModConfigSpec

/** Server settings (`serverconfig/sbwnpc-server.toml` in each world). */
object SquadConfig {
    private val builder = ModConfigSpec.Builder()

    val ACTIVE_SQUAD_CHUNK_LIMIT: ModConfigSpec.IntValue = builder
        .comment(
            "How many squads far from every player keep the ground under them loaded, so they go",
            "on moving and fighting instead of freezing where the player left them. Only squads",
            "with somewhere to be (moving, attacking, retreating) or a fight on count; squads",
            "holding a position with nothing to shoot at sleep. Each one costs about as much as",
            "a player on a small patch of the world. 0 turns it off."
        )
        .defineInRange("activeSquadChunkLimit", 8, 0, 64)

    val NPC_VIEW_DISTANCE: ModConfigSpec.IntValue = builder
        .comment(
            "How far away, in blocks, players see NPCs. Past the server's own view-distance they",
            "are not sent however high this is: a dedicated server needs view-distance of this",
            "divided by 16 or more. Shots past 128 blocks from every player are simulated rather",
            "than fired, whatever this is set to. Higher costs network and client frame rate."
        )
        .defineInRange("npcViewDistance", 256, 64, 512)

    val RECRUIT_INTERVAL_SECONDS: ModConfigSpec.IntValue = builder
        .comment(
            "Seconds of loaded barracks time between individual NPC recruits, including the first.",
            "Initial recruitment and replacements share this limit. Unloading pauses the clock."
        )
        .defineInRange("recruitIntervalSeconds", 30, 1, 3600)

    val SPEC: ModConfigSpec = builder.build()

    fun recruitIntervalTicks(): Int =
        (if (SPEC.isLoaded) RECRUIT_INTERVAL_SECONDS.get() else RECRUIT_INTERVAL_SECONDS.default) * 20

    /** [NPC_VIEW_DISTANCE], or its default while no world's config is loaded — the client asks
     *  before it has joined one, and a config value read then throws. */
    fun npcViewDistance(): Int = if (SPEC.isLoaded) NPC_VIEW_DISTANCE.get() else NPC_VIEW_DISTANCE.default

    /** The same, in the chunks entity tracking counts in. */
    @JvmStatic
    fun npcTrackingChunks(): Int = (npcViewDistance() + 15) / 16
}

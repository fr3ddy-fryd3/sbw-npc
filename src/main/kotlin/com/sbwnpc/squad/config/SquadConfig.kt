package com.sbwnpc.squad.config

import net.neoforged.neoforge.common.ModConfigSpec

/** Server settings (`serverconfig/sbwnpc-server.toml` in each world). */
object SquadConfig {
    private val builder = ModConfigSpec.Builder()

    val ACTIVE_SQUAD_CHUNK_LIMIT: ModConfigSpec.IntValue = builder
        .comment(
            "How many squads far from every player keep the ground under them loaded, so they go",
            "on moving and fighting instead of freezing where the player left them. Only squads",
            "with somewhere to be (moving, attacking, retreating, resupplying) or a fight on count; squads",
            "holding a position with nothing to shoot at sleep. Each one costs about as much as",
            "a player on a small patch of the world. Assigned barracks also stay loaded at their",
            "own positions so reinforcements spawn; they do not count against the squad limit.",
            "Each independent NPC returning to Supply counts as one squad against this limit.",
            "0 turns off both squad and barracks tickets."
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

    val SPEC: ModConfigSpec = builder.build()

    /** [NPC_VIEW_DISTANCE], or its default while no world's config is loaded — the client asks
     *  before it has joined one, and a config value read then throws. */
    fun npcViewDistance(): Int = if (SPEC.isLoaded) NPC_VIEW_DISTANCE.get() else NPC_VIEW_DISTANCE.default

    /** The same, in the chunks entity tracking counts in. */
    @JvmStatic
    fun npcTrackingChunks(): Int = (npcViewDistance() + 15) / 16
}

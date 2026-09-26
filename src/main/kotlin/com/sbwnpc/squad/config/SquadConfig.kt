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

    val SPEC: ModConfigSpec = builder.build()
}

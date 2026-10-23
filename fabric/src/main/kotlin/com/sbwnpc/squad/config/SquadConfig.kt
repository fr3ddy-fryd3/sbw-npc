package com.sbwnpc.squad.config

import com.sbwnpc.squad.npc.NpcClass
import net.neoforged.neoforge.common.ModConfigSpec

/** Server settings (`serverconfig/sbwnpc-server.toml` in each world). */
object SquadConfig : SquadSettings {
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
            "0 turns off both squad and barracks tickets.",
        )
        .defineInRange("activeSquadChunkLimit", DefaultSquadSettings.ACTIVE_SQUAD_CHUNK_LIMIT, 0, 64)

    val NPC_VIEW_DISTANCE: ModConfigSpec.IntValue = builder
        .comment(
            "How far away, in blocks, players see NPCs. Past the server's own view-distance they",
            "are not sent however high this is: a dedicated server needs view-distance of this",
            "divided by 16 or more. Shots past 128 blocks from every player are simulated rather",
            "than fired, whatever this is set to. Higher costs network and client frame rate.",
        )
        .defineInRange("npcViewDistance", DefaultSquadSettings.NPC_VIEW_DISTANCE, 64, 512)

    val RECRUIT_INTERVAL_SECONDS: ModConfigSpec.IntValue = builder
        .comment(
            "Seconds of loaded barracks time between replacements for fallen NPCs, including the first replacement.",
            "Deploy releases the initial garrison together. Unloading pauses the replacement clock.",
        )
        .defineInRange("recruitIntervalSeconds", DefaultSquadSettings.RECRUIT_INTERVAL_SECONDS, 1, 3600)

    val REGULAR_XP = builder.defineInRange("regularExperience", DefaultSquadSettings.REGULAR_XP, 1, 1000000)
    val VETERAN_XP = builder.defineInRange("veteranExperience", DefaultSquadSettings.VETERAN_XP, 2, 1000000)
    val ELITE_XP = builder.defineInRange("eliteExperience", DefaultSquadSettings.ELITE_XP, 3, 1000000)

    private val CLASS_EXPERIENCE = run {
        builder.comment("Class reward multipliers. Applied once to new XP, including deferred rewards; saved XP is unchanged.").push("experienceMultipliers")
        val values = NpcClass.entries.filter { it.canonical == it }.associateWith {
            builder.defineInRange(it.name.lowercase(), DefaultSquadSettings.experienceMultiplier(it), 0.01, 10.0)
        }
        builder.pop()
        values
    }

    override fun experienceMultiplier(cls: NpcClass): Double = CLASS_EXPERIENCE.getValue(cls.canonical).let { if (SPEC.isLoaded) it.get() else it.default }

    override fun experienceThresholds(): List<Int> {
        fun value(v: ModConfigSpec.IntValue) = if (SPEC.isLoaded) v.get() else v.default
        val regular = value(REGULAR_XP)
        val veteran = maxOf(regular + 1, value(VETERAN_XP))
        return listOf(0, regular, veteran, maxOf(veteran + 1, value(ELITE_XP)))
    }

    val SPEC: ModConfigSpec = builder.build()

    override fun recruitIntervalTicks(): Int = (if (SPEC.isLoaded) RECRUIT_INTERVAL_SECONDS.get() else RECRUIT_INTERVAL_SECONDS.default) * 20

    override fun activeSquadChunkLimit(): Int = if (SPEC.isLoaded) ACTIVE_SQUAD_CHUNK_LIMIT.get() else ACTIVE_SQUAD_CHUNK_LIMIT.default

    /** [NPC_VIEW_DISTANCE], or its default while no world's config is loaded — the client asks
     *  before it has joined one, and a config value read then throws. */
    override fun npcViewDistance(): Int = if (SPEC.isLoaded) NPC_VIEW_DISTANCE.get() else NPC_VIEW_DISTANCE.default

    /** The same, in the chunks entity tracking counts in. */
    @JvmStatic
    fun npcTrackingChunks(): Int = (npcViewDistance() + 15) / 16
}

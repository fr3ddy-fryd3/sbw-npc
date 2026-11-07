package com.sbwnpc.squad.config

import com.sbwnpc.squad.npc.NpcClass

/** Gameplay settings supplied by the active loader. */
interface SquadSettings {
    fun activeSquadChunkLimit(): Int
    fun npcViewDistance(): Int
    fun recruitIntervalTicks(): Int
    fun experienceThresholds(): List<Int>
    fun experienceMultiplier(cls: NpcClass): Double
}

/** Values used before a world config is loaded and by plain JVM tests. */
object DefaultSquadSettings : SquadSettings {
    const val ACTIVE_SQUAD_CHUNK_LIMIT = 8
    const val NPC_VIEW_DISTANCE = 256
    const val RECRUIT_INTERVAL_SECONDS = 10
    const val REGULAR_XP = 300
    const val VETERAN_XP = 1200
    const val ELITE_XP = 3500

    override fun activeSquadChunkLimit(): Int = ACTIVE_SQUAD_CHUNK_LIMIT
    override fun npcViewDistance(): Int = NPC_VIEW_DISTANCE
    override fun recruitIntervalTicks(): Int = RECRUIT_INTERVAL_SECONDS * 20
    override fun experienceThresholds(): List<Int> = listOf(0, REGULAR_XP, VETERAN_XP, ELITE_XP)

    /** Every class but the mortar earns this much more, so a rank takes two thirds of the XP; the mortar's slow climb stays. */
    private const val FASTER_RANKS = 1.5

    override fun experienceMultiplier(cls: NpcClass): Double =
        when (cls.canonical) {
            NpcClass.MACHINE_GUNNER, NpcClass.DRONE_OPERATOR -> 0.65 * FASTER_RANKS
            NpcClass.SNIPER, NpcClass.TANK_CREW, NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER -> 0.5 * FASTER_RANKS
            NpcClass.GRENADIER -> 0.75 * FASTER_RANKS
            NpcClass.MORTAR -> 0.35
            else -> FASTER_RANKS
        }
}

/** The loader installs its live settings before gameplay begins. */
object Settings {
    var current: SquadSettings = DefaultSquadSettings
}

package com.sbwnpc.squad.npc

/**
 * Experience tier. Scales survivability and how quick/accurate the NPC is in a firefight.
 * These feed NpcGunAttackGoal (aimTime, spread, semi-fire cadence) and max health.
 */
enum class NpcRank(
    val healthMultiplier: Double,
    val aimTimeTicks: Int,
    val spread: Double,
    val semiFireIntervalMs: Long
) {
    RECRUIT(0.8, 34, 3.0, 500),
    REGULAR(1.0, 22, 1.6, 300),
    VETERAN(1.25, 14, 0.9, 180),
    ELITE(1.6, 8, 0.5, 90);

    fun next(): NpcRank = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = REGULAR

        fun byOrdinal(i: Int): NpcRank = entries.getOrElse(i) { DEFAULT }
    }
}

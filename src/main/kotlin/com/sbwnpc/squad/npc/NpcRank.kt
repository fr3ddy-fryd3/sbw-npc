package com.sbwnpc.squad.npc

/**
 * Experience tier. Scales survivability and how quick/accurate the NPC is in a firefight.
 * These feed NpcGunAttackGoal (aimTime, spread, semi-fire cadence) and max health.
 *
 * Deliberately not very lethal even at the top: ELITE sits roughly where a "competent but
 * beatable" opponent should, RECRUIT sprays wildly and is slow on the trigger.
 *
 * TODO (later): detection radius, night/visibility coefficient, suppression resistance per rank.
 */
enum class NpcRank(
    val healthMultiplier: Double,
    val aimTimeTicks: Int,
    val spread: Double,
    val semiFireIntervalMs: Long
) {
    RECRUIT(0.7, 55, 7.0, 900),
    REGULAR(0.9, 35, 5.5, 700),
    VETERAN(1.1, 24, 4.0, 550),
    ELITE(1.3, 16, 3.0, 450);

    fun next(): NpcRank = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = REGULAR

        fun byOrdinal(i: Int): NpcRank = entries.getOrElse(i) { DEFAULT }
    }
}

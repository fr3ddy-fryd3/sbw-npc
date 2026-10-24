package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcRank

/**
 * How much wider than the weapon's own spread an NPC vehicle gunner's shots scatter, by rank.
 *
 * SBW lays a turret on a perfect lead every tick, so the shot's spread is the only error a gunner
 * has. The weapons' own spreads are small (0.25 for a helicopter cannon, 0.5 to 1.0 for an APC's)
 * next to infantry's 3 to 7, so this scales the weapon's spread rather than adding a rifleman's to it.
 */
object TurretGunnerAccuracy {
    private const val ELITE = 1.5
    private const val VETERAN = 2.0
    private const val REGULAR = 2.75
    private const val RECRUIT = 4.0

    fun spread(weaponSpread: Double, rank: NpcRank): Double =
        weaponSpread *
            when (rank) {
                NpcRank.ELITE -> ELITE
                NpcRank.VETERAN -> VETERAN
                NpcRank.REGULAR -> REGULAR
                NpcRank.RECRUIT -> RECRUIT
            }
}

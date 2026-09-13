package com.sbwnpc.squad.npc

import net.minecraft.resources.ResourceLocation

private fun loc(path: String) = ResourceLocation.fromNamespaceAndPath("superbwarfare", path)

/**
 * Combat role. Determines the default weapon and a couple of tuning multipliers applied on top
 * of the NPC's rank (base engagement range is 24 blocks, base spread comes from [NpcRank]).
 */
enum class NpcClass(
    val weaponId: ResourceLocation,
    val shootDistanceMultiplier: Double = 1.0,
    val accuracyMultiplier: Double = 1.0,
) {
    RIFLEMAN(loc("ak_12"), shootDistanceMultiplier = 2.0),
    MACHINE_GUNNER(loc("rpk"), shootDistanceMultiplier = 1.5),
    SNIPER(loc("svd"), shootDistanceMultiplier = 3.0, accuracyMultiplier = 1.0 / 1.2),
    GRENADIER(loc("m_79"), shootDistanceMultiplier = 1.5),

    /** Carries a sidearm for self-defence; its real job (MortarOperatorBehaviour) is manning a nearby
     *  placed MortarEntity, which the MORTAR_LOADER on the same squad keeps supplied. */
    MORTAR_OPERATOR(loc("m_1911")),

    /** Keeps its squad's manned mortar topped up with shells (MortarLoaderBehaviour) — no combat job. */
    MORTAR_LOADER(loc("m_1911"));

    fun next(): NpcClass = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = RIFLEMAN

        fun byOrdinal(i: Int): NpcClass = entries.getOrElse(i) { DEFAULT }
    }
}

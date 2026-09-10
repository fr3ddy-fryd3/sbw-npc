package com.sbwnpc.squad.npc

import net.minecraft.resources.ResourceLocation

/**
 * Combat role. Determines the default weapon (and later: behaviour tuning like preferred
 * engagement range, whether to suppress vs aimed fire, grenade/mortar goals).
 */
enum class NpcClass(val weaponId: ResourceLocation) {
    RIFLEMAN(ResourceLocation.fromNamespaceAndPath("superbwarfare", "ak_12")),
    MACHINE_GUNNER(ResourceLocation.fromNamespaceAndPath("superbwarfare", "rpk")),
    SNIPER(ResourceLocation.fromNamespaceAndPath("superbwarfare", "svd")),
    GRENADIER(ResourceLocation.fromNamespaceAndPath("superbwarfare", "m_79"));

    fun next(): NpcClass = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = RIFLEMAN

        fun byOrdinal(i: Int): NpcClass = entries.getOrElse(i) { DEFAULT }
    }
}

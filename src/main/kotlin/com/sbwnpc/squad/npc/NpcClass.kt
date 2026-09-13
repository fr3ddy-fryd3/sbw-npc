package com.sbwnpc.squad.npc

import net.minecraft.resources.ResourceLocation

private fun loc(path: String) = ResourceLocation.fromNamespaceAndPath("superbwarfare", path)

/**
 * Combat role. Determines the weapon pool (one is picked at random per NPC in
 * `NpcEntity.applyRole()`, purely for visual variety across NPCs of the same class — never
 * re-rolled after spawn) and a couple of tuning multipliers applied on top of the NPC's rank (base
 * engagement range is 24 blocks, base spread comes from [NpcRank]). All weapon IDs verified against
 * the real SuperbWarfare source (`./SuperbWarfare`, a composite build compiled directly, not a
 * guessed/stale reference) — see `PHASE_5_8_PLAN.md`.
 */
enum class NpcClass(
    val weaponPool: List<ResourceLocation>,
    val shootDistanceMultiplier: Double = 1.0,
    val accuracyMultiplier: Double = 1.0,
    val speedMultiplier: Double = 1.4, // "everyone else" default per user request — was the one global value before
) {
    RIFLEMAN(listOf(loc("ak_47"), loc("ak_12")), shootDistanceMultiplier = 2.0),
    MACHINE_GUNNER(listOf(loc("rpk"), loc("m_60")), shootDistanceMultiplier = 1.5, speedMultiplier = 1.3),
    SNIPER(listOf(loc("svd"), loc("awm")), shootDistanceMultiplier = 3.0, accuracyMultiplier = 1.0 / 1.4, speedMultiplier = 1.3), // a bit tighter spread per user request (was /1.2)

    /** Only ever spawned individually (`SquadPreset.SINGLE`) — removed from every squad-composition
     *  preset in favour of [MEDIC], per user decision. Still a fully valid class otherwise (weapon,
     *  grenade, digging-in all work normally for a manually-placed grenadier). */
    GRENADIER(listOf(loc("m_79")), shootDistanceMultiplier = 1.5, speedMultiplier = 1.3),

    /** Support role: heals wounded squadmates (`MedicHealBehaviour`) between/instead of fighting
     *  with its own SMG — see that behaviour's doc comment for the shoot-vs-heal priority. Replaces
     *  `GRENADIER`'s slot(s) in the squad-composition presets ("8: Standard", "16: Large"). */
    MEDIC(listOf(loc("mp_5"), loc("vector")), speedMultiplier = 1.5),

    /** Carries a sidearm for self-defence; its real job (MortarOperatorBehaviour) is manning a nearby
     *  placed MortarEntity, which the MORTAR_LOADER on the same squad keeps supplied. */
    MORTAR_OPERATOR(listOf(loc("glock_18"), loc("mp_443"))),

    /** Keeps its squad's manned mortar topped up with shells (MortarLoaderBehaviour) — no combat job. */
    MORTAR_LOADER(listOf(loc("glock_18"), loc("mp_443")));

    fun next(): NpcClass = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = RIFLEMAN

        fun byOrdinal(i: Int): NpcClass = entries.getOrElse(i) { DEFAULT }
    }
}

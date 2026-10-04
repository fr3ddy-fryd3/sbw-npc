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
    /** Support roles stay behind the assault and back off when an enemy closes inside this range. */
    val minimumCombatDistance: Double = 0.0,
) {
    RIFLEMAN(listOf(loc("ak_47"), loc("ak_12")), shootDistanceMultiplier = 2.0),
    MACHINE_GUNNER(listOf(loc("rpk"), loc("m_60")), shootDistanceMultiplier = 3.0, speedMultiplier = 1.3, minimumCombatDistance = 56.0),
    SNIPER(listOf(loc("svd"), loc("awm")), shootDistanceMultiplier = 3.0, accuracyMultiplier = 1.0 / 1.4, speedMultiplier = 1.3, minimumCombatDistance = 64.0), // a bit tighter spread per user request (was /1.2)

    /** Only ever spawned individually (`SquadPreset.SINGLE`) — removed from every squad-composition
     *  preset in favour of [MEDIC], per user decision. Still a fully valid class otherwise (weapon,
     *  grenade, digging-in all work normally for a manually-placed grenadier). */
    GRENADIER(listOf(loc("m_79")), shootDistanceMultiplier = 1.5, speedMultiplier = 1.3),

    /** Support role: heals wounded squadmates (`MedicHealBehaviour`) between/instead of fighting
     *  with its own SMG — see that behaviour's doc comment for the shoot-vs-heal priority. Replaces
     *  `GRENADIER`'s slot(s) in the squad-composition presets ("8: Standard", "16: Large").
     *
     *  Paced and positioned like a SNIPER: the long engagement range stops normal advancing,
     *  and minimumCombatDistance keeps its firing positions and idle assault posts in the rear.
     *  MedicHealBehaviour itself temporarily overrides the 1.3 pace to
     *  [com.sbwnpc.squad.entity.ai.MedicHealBehaviour] sprint speed while actually running to treat
     *  someone — see that class. */
    MEDIC(listOf(loc("mp_5"), loc("vector")), shootDistanceMultiplier = 3.0, speedMultiplier = 1.3, minimumCombatDistance = 64.0),

    /** The medic's SMGs for self-defence; its real job (MortarOperatorBehaviour) is manning a nearby
     *  placed MortarEntity, which the MORTAR_LOADER on the same squad keeps supplied. */
    MORTAR_OPERATOR(listOf(loc("mp_5"), loc("vector"))),

    /** Keeps its squad's manned mortar loaded (MortarLoaderBehaviour) from the shells it carries, and
     *  fetches more from a Supply — no combat job. Same SMGs as the operator. */
    MORTAR_LOADER(listOf(loc("mp_5"), loc("vector"))),

    /** Permanent vehicle crewman. Its sidearm is only for the brief period after its vehicle is destroyed. */
    TANK_CREW(listOf(loc("glock_18"), loc("mp_443"))),

    /** Flies its squad's helicopter from seat 0 — see HelicopterPilotBehaviour. Sidearm only, and
     *  only relevant once it is back on the ground without an aircraft. */
    HELICOPTER_PILOT(listOf(loc("glock_18"), loc("mp_443"))),

    /** Works the gunship turret from seat 1 (HelicopterGunnerBehaviour); same sidearm caveat. */
    HELICOPTER_GUNNER(listOf(loc("glock_18"), loc("mp_443"))),
    // Flies kamikaze drones at targets 80-150 blocks out (by rank) — see DroneOperatorBehaviour.
    // Same rifles as RIFLEMAN (per user request): once the drones are spent it fights on as an
    // ordinary rifleman rather than plinking with a sidearm.
    DRONE_OPERATOR(listOf(loc("ak_47"), loc("ak_12")), shootDistanceMultiplier = 2.0, speedMultiplier = 1.3);

    fun next(): NpcClass = entries[(ordinal + 1) % entries.size]

    /**
     * Every round this class starts with for [weapon], the loaded magazine included (it is filled
     * from these). Counted in rounds per class and gun, per user call: a flat 120 for everyone gave
     * an AWM 24 reloads, an M79 120 grenades and an M60 barely one belt.
     */
    fun startingRounds(weapon: ResourceLocation): Int = when (this) {
        RIFLEMAN -> 210
        MACHINE_GUNNER -> if (weapon.path == "m_60") 400 else 320
        SNIPER -> if (weapon.path == "awm") 50 else 60
        GRENADIER -> 20
        MEDIC, MORTAR_OPERATOR, MORTAR_LOADER -> 180
        DRONE_OPERATOR -> 120
        TANK_CREW, HELICOPTER_PILOT, HELICOPTER_GUNNER -> 85
    }

    companion object {
        val DEFAULT = RIFLEMAN

        fun byOrdinal(i: Int): NpcClass = entries.getOrElse(i) { DEFAULT }
    }
}

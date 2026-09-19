package com.sbwnpc.squad.npc

/** What the recruit tool deploys in one click: a single NPC, or a whole pre-built squad.
 *  [spacing] is the sideways gap `SquadToolItem.deployLine` puts between adjacent members — plain
 *  infantry line abreast at the usual 2 blocks, but [DRONE_TEAM] widens it: a drone shot down
 *  during launch (still climbing right over the operator) blasts where it is, and 2 blocks puts
 *  every other operator inside that radius. */
enum class SquadPreset(val label: String, val composition: List<NpcClass>, val spacing: Double = 2.0) {
    SINGLE("Single", emptyList()),
    FIVE("5: Riflemen", List(4) { NpcClass.RIFLEMAN } + NpcClass.MEDIC),
    // Keep the removed preset's ordinal reserved: recruit-tool configurations persist ordinals.
    REMOVED_SIX("", emptyList()),
    SEVEN(
        "7: Standard",
        List(4) { NpcClass.RIFLEMAN } + NpcClass.SNIPER + NpcClass.MACHINE_GUNNER + NpcClass.MEDIC
    ),
    SIXTEEN(
        "16: Large",
        List(10) { NpcClass.RIFLEMAN } + List(2) { NpcClass.SNIPER } + List(2) { NpcClass.MACHINE_GUNNER } + List(2) { NpcClass.MEDIC }
    ),
    MORTAR_CREW("Mortar Crew", listOf(NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER)),
    // Label stayed generic ("Tank Crew") once TankModel let the GUI pick which of the three
    // models the crew actually rides; the enum name is kept as-is, only ordinal position matters.
    T90_CREW("Tank Crew", listOf(NpcClass.TANK_CREW)),
    DRONE_TEAM("Drone Team", List(4) { NpcClass.DRONE_OPERATOR }, spacing = 10.0),

    /** Mi-28 crew: pilot in seat 0, gunner on the turret in seat 1. The pilot MUST be first — SBW
     *  treats a helicopter's first passenger as the one flying it, and zeroes every control input
     *  on an aircraft that has none. */
    HELI_CREW("Heli Crew", listOf(NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER));

    fun next(): SquadPreset {
        var next = entries[(ordinal + 1) % entries.size]
        while (next == REMOVED_SIX) {
            next = entries[(next.ordinal + 1) % entries.size]
        }
        return next
    }

    fun previous(): SquadPreset {
        var prev = entries[(ordinal - 1 + entries.size) % entries.size]
        while (prev == REMOVED_SIX) {
            prev = entries[(prev.ordinal - 1 + entries.size) % entries.size]
        }
        return prev
    }

    companion object {
        val DEFAULT = SINGLE
        // Existing tool configurations that selected the removed six-NPC option now use SEVEN.
        fun byOrdinal(i: Int): SquadPreset = entries.getOrElse(i) { DEFAULT }.let {
            if (it == REMOVED_SIX) SEVEN else it
        }
    }
}

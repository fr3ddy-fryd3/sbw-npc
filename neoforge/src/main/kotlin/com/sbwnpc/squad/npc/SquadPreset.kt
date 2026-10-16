package com.sbwnpc.squad.npc

/** What the recruit tool deploys in one click: a single NPC, or a whole pre-built squad.
 *  [spacing] is the sideways gap `SquadToolItem.deployLine` puts between adjacent members — plain
 *  infantry line abreast at the usual 2 blocks, but [DRONE_TEAM] widens it: a drone shot down
 *  during launch (still climbing right over the operator) blasts where it is, and 2 blocks puts
 *  every other operator inside that radius. */
enum class SquadPreset(
    val label: String,
    val composition: List<NpcClass>,
    val spacing: Double = 2.0,
    /** Infantry that deploys already standing in its MOVE grid, with a MOVE order on the spot. */
    val grid: Boolean = false
) {
    SINGLE("Single", emptyList()),
    FIVE("5: Riflemen", List(4) { NpcClass.RIFLEMAN } + NpcClass.MEDIC, grid = true),
    // Keep the removed preset's ordinal reserved: recruit-tool configurations persist ordinals.
    REMOVED_SIX("", emptyList()),
    SEVEN(
        "7: Standard",
        List(4) { NpcClass.RIFLEMAN } + NpcClass.SNIPER + NpcClass.MACHINE_GUNNER + NpcClass.MEDIC,
        grid = true
    ),
    SIXTEEN(
        "16: Large",
        List(10) { NpcClass.RIFLEMAN } + List(2) { NpcClass.SNIPER } + List(2) { NpcClass.MACHINE_GUNNER } + List(2) { NpcClass.MEDIC },
        grid = true
    ),
    MORTAR_CREW("Mortar Crew", listOf(NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER)),
    // Label stayed generic ("Tank Crew") once TankModel let the GUI pick which of the three
    // models the crew actually rides; the enum name is kept as-is, only ordinal position matters.
    T90_CREW("Tank Crew", listOf(NpcClass.TANK_CREW)),
    DRONE_TEAM("Drone Team", List(4) { NpcClass.DRONE_OPERATOR }, spacing = 10.0),

    /** Mi-28 crew: pilot in seat 0, gunner on the turret in seat 1. The pilot MUST be first — SBW
     *  treats a helicopter's first passenger as the one flying it, and zeroes every control input
     *  on an aircraft that has none. */
    /** Who actually deploys depends on the airframe picked in the GUI — see [HelicopterModel.crew],
     *  which is what the recruit tool uses instead of this list. */
    HELI_CREW("Heli Crew", HelicopterModel.DEFAULT.crew),

    // Added last to keep the saved ordinals of everything above; shown after SIXTEEN (see ORDER).
    THIRTY_TWO(
        "32: Company",
        List(20) { NpcClass.RIFLEMAN } + List(4) { NpcClass.SNIPER } + List(4) { NpcClass.MACHINE_GUNNER } + List(4) { NpcClass.MEDIC },
        grid = true
    );

    fun next(): SquadPreset = ORDER[(ORDER.indexOf(this) + 1) % ORDER.size]

    fun previous(): SquadPreset = ORDER[(ORDER.indexOf(this) - 1 + ORDER.size) % ORDER.size]

    companion object {
        val DEFAULT = SINGLE
        /** The order the recruit tool steps through them: infantry by size, then the crews. */
        private val ORDER = listOf(SINGLE, FIVE, SEVEN, SIXTEEN, THIRTY_TWO, MORTAR_CREW, T90_CREW, DRONE_TEAM, HELI_CREW)
        // Existing tool configurations that selected the removed six-NPC option now use SEVEN.
        fun byOrdinal(i: Int): SquadPreset = entries.getOrElse(i) { DEFAULT }.let {
            if (it == REMOVED_SIX) SEVEN else it
        }
    }
}

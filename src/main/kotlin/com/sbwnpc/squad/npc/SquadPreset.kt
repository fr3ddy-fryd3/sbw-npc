package com.sbwnpc.squad.npc

/** What the recruit tool deploys in one click: a single NPC, or a whole pre-built squad. */
enum class SquadPreset(val label: String, val composition: List<NpcClass>) {
    SINGLE("Single", emptyList()),
    FOUR("4: Riflemen", List(4) { NpcClass.RIFLEMAN }),
    // Keep the removed preset's ordinal reserved: recruit-tool configurations persist ordinals.
    REMOVED_SIX("", emptyList()),
    EIGHT(
        "8: Standard",
        List(4) { NpcClass.RIFLEMAN } + List(2) { NpcClass.SNIPER } + NpcClass.MEDIC + NpcClass.MACHINE_GUNNER
    ),
    SIXTEEN(
        "16: Large",
        List(10) { NpcClass.RIFLEMAN } + List(2) { NpcClass.SNIPER } + List(2) { NpcClass.MACHINE_GUNNER } + List(2) { NpcClass.MEDIC }
    ),
    MORTAR_CREW("Mortar Crew", listOf(NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER)),
    T90_CREW("T-90 Crew", listOf(NpcClass.TANK_CREW)),
    DRONE_TEAM("Drone Team", listOf(NpcClass.DRONE_OPERATOR));

    fun next(): SquadPreset {
        var next = entries[(ordinal + 1) % entries.size]
        while (next == REMOVED_SIX) {
            next = entries[(next.ordinal + 1) % entries.size]
        }
        return next
    }

    companion object {
        val DEFAULT = SINGLE
        // Existing tool configurations that selected the removed six-NPC option now use EIGHT.
        fun byOrdinal(i: Int): SquadPreset = entries.getOrElse(i) { DEFAULT }.let {
            if (it == REMOVED_SIX) EIGHT else it
        }
    }
}

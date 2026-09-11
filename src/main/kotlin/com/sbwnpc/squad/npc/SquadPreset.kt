package com.sbwnpc.squad.npc

/** What the recruit tool deploys in one click: a single NPC, or a whole pre-built squad. */
enum class SquadPreset(val label: String, val composition: List<NpcClass>) {
    SINGLE("Single", emptyList()),
    FOUR("4: Riflemen", List(4) { NpcClass.RIFLEMAN }),
    SIX("6: Rifle+Sniper", List(4) { NpcClass.RIFLEMAN } + List(2) { NpcClass.SNIPER }),
    EIGHT(
        "8: Standard",
        List(4) { NpcClass.RIFLEMAN } + List(2) { NpcClass.SNIPER } + NpcClass.GRENADIER + NpcClass.MACHINE_GUNNER
    ),
    SIXTEEN(
        "16: Large",
        List(10) { NpcClass.RIFLEMAN } + List(2) { NpcClass.SNIPER } + List(2) { NpcClass.MACHINE_GUNNER } + List(2) { NpcClass.GRENADIER }
    ),
    MORTAR_CREW("Mortar Crew", listOf(NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER));

    fun next(): SquadPreset = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = SINGLE
        fun byOrdinal(i: Int): SquadPreset = entries.getOrElse(i) { DEFAULT }
    }
}

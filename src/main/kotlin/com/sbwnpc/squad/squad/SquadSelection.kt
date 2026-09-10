package com.sbwnpc.squad.squad

import java.util.UUID

/**
 * Transient, server-side "what does this player have selected with the squad tool" state.
 * - a loose set of NPCs not yet in a squad (for forming one), or
 * - a single existing squad (for commanding it).
 */
object SquadSelection {
    private val loose = HashMap<UUID, MutableSet<UUID>>()
    private val squad = HashMap<UUID, UUID>()

    fun looseOf(player: UUID): Set<UUID> = loose[player].orEmpty()
    fun selectedSquad(player: UUID): UUID? = squad[player]

    fun toggleLoose(player: UUID, npc: UUID) {
        val set = loose.getOrPut(player) { LinkedHashSet() }
        if (!set.remove(npc)) set.add(npc)
        squad.remove(player)
    }

    fun selectSquad(player: UUID, squadId: UUID) {
        squad[player] = squadId
        loose.remove(player)
    }

    fun clear(player: UUID) {
        loose.remove(player)
        squad.remove(player)
    }
}

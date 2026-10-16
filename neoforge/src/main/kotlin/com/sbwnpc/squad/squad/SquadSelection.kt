package com.sbwnpc.squad.squad

import java.util.UUID

/**
 * Transient, server-side "what does this player have selected with the squad tool" state.
 * - a loose set of NPCs not yet in a squad (for forming one), and/or
 * - a single existing squad (the one being commanded), and/or
 * - a one-shot "next air-click sets this squad's objective" arm.
 */
object SquadSelection {
    private val loose = HashMap<UUID, MutableSet<UUID>>()
    private val squad = HashMap<UUID, UUID>()
    private val awaitingObjective = HashMap<UUID, UUID>()
    private val awaitingFocus = HashMap<UUID, UUID>()

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
        awaitingObjective.remove(player)
        awaitingFocus.remove(player)
    }

    fun clearAll() {
        loose.clear()
        squad.clear()
        awaitingObjective.clear()
        awaitingFocus.clear()
    }

    fun armObjective(player: UUID, squadId: UUID) {
        awaitingObjective[player] = squadId
    }

    /** Consumes and returns the squad awaiting an objective, if any. */
    fun takeObjectiveArm(player: UUID): UUID? = awaitingObjective.remove(player)

    fun armFocus(player: UUID, squadId: UUID) {
        awaitingFocus[player] = squadId
    }

    /** Consumes and returns the squad awaiting a focus-entity pick, if any. */
    fun takeFocusArm(player: UUID): UUID? = awaitingFocus.remove(player)

}

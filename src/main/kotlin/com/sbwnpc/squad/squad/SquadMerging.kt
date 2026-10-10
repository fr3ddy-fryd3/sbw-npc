package com.sbwnpc.squad.squad

import com.sbwnpc.squad.npc.NpcClass
import java.util.UUID

/** Transfers living infantry places; soldier identity and equipment stay in entity NBT. */
object SquadMerging {
    private val crew = setOf(NpcClass.TANK_CREW, NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER, NpcClass.MORTAR)

    fun problem(source: Squad?, destination: Squad?, owner: UUID): String? =
        when {
            source == null || destination == null -> "Squad no longer exists"

            source.id == destination.id -> "Choose another squad"

            source.owner != owner || destination.owner != owner -> "Both squads must belong to you"

            source.faction != destination.faction -> "Both squads must have the same faction"

            source.barracks != null || destination.barracks != null -> "Detach both squads from their barracks first"

            source.members.isEmpty() || destination.members.isEmpty() -> "Both squads must contain soldiers"

            source.recruitmentRoster == null || destination.recruitmentRoster == null -> "Load every soldier to resolve the old roster first"

            (source.originalComposition + destination.originalComposition).any { it.canonical in crew } -> "Only infantry squads can merge"

            (source.recruitmentRoster!!.slots + destination.recruitmentRoster!!.slots).any { it.role.canonical in crew } -> "Only infantry squads can merge"

            source.recruitmentRoster!!.slots.mapNotNull { it.occupant }.toSet() != source.members.toSet() ||
                destination.recruitmentRoster!!.slots.mapNotNull { it.occupant }.toSet() != destination.members.toSet() -> "Load every soldier to resolve its roster first"

            source.members.any { it in destination.members } -> "Squads contain duplicate members"

            source.lastSeenDim != null && destination.lastSeenDim != null && source.lastSeenDim != destination.lastSeenDim -> "Both squads must be in the same dimension"

            else -> null
        }

    internal fun transfer(source: Squad, destination: Squad, now: Long) {
        source.tactics.deactivate(now, "squad_merged")
        destination.tactics.deactivate(now, "squad_merged")
        val members = (destination.members + source.members).toSet()
        val slots = (destination.recruitmentRoster!!.slots + source.recruitmentRoster!!.slots)
            .filter { it.occupant in members }
            .distinctBy { it.occupant }
            .map { RecruitmentRoster.Slot(it.id, it.role, it.occupant, it.recruited, active = true) }
        destination.members.clear()
        destination.members.addAll(members)
        destination.recruitmentRoster = RecruitmentRoster(slots.toMutableList())
        destination.originalComposition = slots.map { it.role }
        destination.orderStamp++
        destination.moveFormationReady = false
        destination.moveRallySince = 0L
        SquadSelection.replace(source.id, destination.id)
    }
}

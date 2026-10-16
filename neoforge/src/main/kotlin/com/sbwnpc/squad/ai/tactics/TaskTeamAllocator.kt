package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import java.util.UUID

/** Task-scoped small groups retain unaffected memberships through casualties and reinforcement. */
class TaskTeamAllocator(private val capacity: Int = 6) {
    private data class Cell(val id: AgentId, val duty: CombatDuty, val members: MutableSet<AgentId> = LinkedHashSet())
    private val cells = ArrayList<Cell>()
    private var serial = 0L
    init {
        require(capacity > 0)
    }

    fun assign(squad: UUID, intents: List<PositionIntent>): List<TeamMission> {
        require(intents.map { it.actor }.distinct().size == intents.size)
        val current = intents.associateBy { it.actor }
        cells.forEach { cell -> cell.members.removeIf { current[it]?.duty != cell.duty } }
        cells.removeIf { it.members.isEmpty() }
        val retained = cells.flatMapTo(HashSet()) { it.members }
        for (intent in intents.sortedBy { it.actor.id.toString() }) {
            if (intent.actor in retained) continue
            val cell = cells.filter { it.duty == intent.duty && it.members.size < capacity }.minByOrNull { candidate ->
                candidate.members.sumOf { current.getValue(it).origin.horizontalDistanceSquared(intent.origin) } / candidate.members.size
            } ?: Cell(AgentId(AgentLevel.TEAM, UUID.nameUUIDFromBytes("$squad:${intent.duty}:${serial++}".toByteArray())), intent.duty).also(cells::add)
            cell.members += intent.actor
        }
        return cells.map { cell -> TeamMission(cell.id, cell.members.map(current::getValue)) }
    }
}

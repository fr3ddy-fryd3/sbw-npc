package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TaskTeamAllocatorTest {
    private val squad = UUID(1, 1)
    private fun actor(id: Long, duty: CombatDuty = CombatDuty.FIRE) = PositionIntent(AgentId(AgentLevel.NPC, UUID(0, id)), duty, Point3(id.toDouble(), 1.0, 0.0), Point3(100.0, 1.0, 0.0), null)
    private fun owners(teams: List<TeamMission>) = teams.flatMap { team -> team.members.map { it.actor to team.agent } }.toMap()

    @Test fun `casualties reinforcement and task changes preserve unaffected small groups`() {
        val allocator = TaskTeamAllocator()
        val initial = (1L..32L).map { actor(it) }
        val before = allocator.assign(squad, initial)
        assertTrue(before.all { it.members.size <= 6 })
        assertEquals(32, owners(before).size)
        val survivors = initial.drop(3)
        val added = survivors + actor(40) + actor(41)
        val after = allocator.assign(squad, added)
        survivors.forEach { assertEquals(owners(before)[it.actor], owners(after)[it.actor]) }
        assertTrue(after.all { it.members.size <= 6 })
        val changed = added.map { if (it.actor.id == UUID(0, 10)) it.copy(duty = CombatDuty.MOVE) else it }
        val changedOwners = owners(allocator.assign(squad, changed))
        added.filter { it.actor.id != UUID(0, 10) }.forEach { assertEquals(owners(after)[it.actor], changedOwners[it.actor]) }
        assertNotEquals(owners(after)[actor(10).actor], changedOwners[actor(10).actor])
    }
}

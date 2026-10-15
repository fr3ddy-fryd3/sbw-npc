package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.hypot

class OperationTacticsTest {
    private val objective = Point3(100.0, 64.0, 100.0)
    private fun id(n: Int) = UUID(0, n.toLong())
    private fun member(n: Int, x: Double, ready: Boolean = true) = AttackMember(id(n), Point3(x, 64.0, 100.0), ready)

    @Test fun `deploy assigns no attack roles`() {
        assertEquals(AttackRoles.NONE, OperationTactics.roles(AttackPhase.DEPLOY, objective, listOf(id(1)), listOf(member(1, 40.0)), listOf(objective), emptySet()))
    }

    @Test fun `assault places movers on distinct spots inside the objective`() {
        val movers = (1..12).map(::id)
        val roles = OperationTactics.roles(AttackPhase.ASSAULT, objective, movers, movers.indices.map { member(it + 1, 40.0) }, emptyList(), emptySet())
        assertEquals(movers.toSet(), roles.assault.keys)
        assertTrue(roles.clearing.isEmpty())
        assertEquals(12, roles.assault.values.toSet().size)
        roles.assault.values.forEach { assertTrue(hypot(it.x - objective.x, it.z - objective.z) <= 12.0) }
    }

    @Test fun `clearing sends the nearest ready members and keeps the team stable`() {
        val members = (1..8).map { member(it, 100.0 + it * 2) } + member(9, 150.0, ready = false)
        val straggler = Point3(160.0, 64.0, 100.0)
        val roles = OperationTactics.roles(AttackPhase.CLEAR, objective, (1..8).map(::id), members, listOf(straggler), emptySet())
        assertEquals(setOf(id(7), id(8)), roles.clearing.keys)
        assertTrue(roles.clearing.values.all { it == straggler })
        assertTrue(roles.assault.keys.none { it in roles.clearing })
        val again = OperationTactics.roles(AttackPhase.CLEAR, objective, (1..8).map(::id), members, listOf(straggler), setOf(id(1), id(2)))
        assertEquals(setOf(id(1), id(2)), again.clearing.keys)
    }

    @Test fun `apply rewrites only the members with a role`() {
        val intent = { n: Int -> PositionIntent(AgentId(AgentLevel.NPC, id(n)), CombatDuty.FIRE, Point3(40.0, 64.0, 100.0), Point3(60.0, 64.0, 100.0), objective, requireShelter = true) }
        val mission = SquadMission(AgentId(AgentLevel.SQUAD, id(100)), listOf(TeamMission(AgentId(AgentLevel.TEAM, id(101)), listOf(intent(1), intent(2), intent(3)))))
        val slot = Point3(104.0, 64.0, 100.0)
        val target = Point3(160.0, 64.0, 100.0)
        val applied = OperationTactics.apply(mission, AttackRoles(mapOf(id(1) to slot), mapOf(id(2) to target))).teams.single().members
        assertEquals(CombatDuty.MOVE, applied[0].duty)
        assertEquals(slot, applied[0].anchor)
        assertTrue(applied[0].assault && !applied[0].requireShelter)
        assertEquals(CombatDuty.SEARCH, applied[1].duty)
        assertEquals(target, applied[1].inspection)
        assertEquals(intent(3), applied[2])
        assertSame(mission, OperationTactics.apply(mission, AttackRoles.NONE))
    }
}

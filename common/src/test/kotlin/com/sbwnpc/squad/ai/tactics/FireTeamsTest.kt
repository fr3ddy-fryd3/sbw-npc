package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.npc.NpcClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class FireTeamsTest {
    private val squad = UUID(1, 1)
    private fun man(id: Long, role: NpcClass = NpcClass.RIFLEMAN) = FireTeams.Man(UUID(0, id), role, Point3(id.toDouble(), 1.0, 0.0))
    private fun owners(teams: Map<*, List<UUID>>) = teams.flatMap { (team, men) -> men.map { it to team } }.toMap()
    private fun FireTeams.of(men: List<FireTeams.Man>, steady: Boolean = true, roster: Set<UUID> = men.mapTo(HashSet()) { it.id }) = assign(squad, men, roster, steady)

    @Test fun `up to three men are one team, four to twelve two, more a team per six`() {
        assertEquals(1, FireTeams().of((1L..3L).map { man(it) }).size)
        assertEquals(listOf(2, 2), FireTeams().of((1L..4L).map { man(it) }).values.map { it.size })
        assertEquals(listOf(3, 2), FireTeams().of((1L..5L).map { man(it) }).values.map { it.size }.sortedDescending())
        assertEquals(2, FireTeams().of((1L..12L).map { man(it) }).size)
        assertEquals(3, FireTeams().of((1L..13L).map { man(it) }).size)
    }

    @Test fun `machine gunners and assault men are spread over the teams, snipers stay in the first`() {
        val men = listOf(man(1, NpcClass.MACHINE_GUNNER), man(2, NpcClass.MACHINE_GUNNER), man(3, NpcClass.GRENADIER), man(4, NpcClass.SHOTGUNNER), man(5, NpcClass.SNIPER), man(6, NpcClass.SNIPER)) +
            (7L..8L).map { man(it) }
        val teams = FireTeams().of(men).values.toList()
        val roles = men.associate { it.id to it.role }
        teams.forEach { team ->
            assertEquals(1, team.count { roles[it] == NpcClass.MACHINE_GUNNER })
            assertEquals(1, team.count { roles[it] == NpcClass.GRENADIER || roles[it] == NpcClass.SHOTGUNNER })
        }
        assertEquals(1, teams.count { team -> team.any { roles[it] == NpcClass.SNIPER } })
    }

    @Test fun `kept men stay through casualties and reinforcement, and sizes even out only when steady`() {
        val teams = FireTeams()
        val men = (1L..8L).map { man(it) }
        val before = owners(teams.of(men))
        val first = before.filterValues { it == before.getValue(UUID(0, 1)) }.keys.toList()
        val survivors = men.filter { it.id != first.first() } + man(20)
        val after = owners(teams.of(survivors))
        survivors.filter { it.id in before }.forEach { assertEquals(before[it.id], after[it.id]) }
        // Two of one team lost mid-manoeuvre: nobody crosses over until the squad is steady again.
        val beaten = men.filter { it.id !in first.take(2) }
        val held = owners(teams.of(beaten, steady = false))
        beaten.forEach { assertEquals(before[it.id], held[it.id]) }
        val balanced = teams.of(beaten).values.map { it.size }
        assertTrue(balanced.max() - balanced.min() < FireTeams.DRIFT)
    }

    @Test fun `a man away from the squad for a moment keeps his team`() {
        val teams = FireTeams()
        val men = (1L..8L).map { man(it) }
        val before = owners(teams.of(men))
        val riding = men.drop(2)
        val away = teams.of(riding, roster = men.mapTo(HashSet()) { it.id })
        assertTrue(away.values.flatten().none { it == men[0].id || it == men[1].id })
        assertEquals(before, owners(teams.of(men)))
    }

    @Test fun `bounding goes round every team in turn, a gun covering the first rush`() {
        val a = com.sbwnpc.squad.ai.planning.AgentId(com.sbwnpc.squad.ai.planning.AgentLevel.TEAM, UUID(9, 1))
        val b = com.sbwnpc.squad.ai.planning.AgentId(com.sbwnpc.squad.ai.planning.AgentLevel.TEAM, UUID(9, 2))
        val c = com.sbwnpc.squad.ai.planning.AgentId(com.sbwnpc.squad.ai.planning.AgentLevel.TEAM, UUID(9, 3))
        val movers = mapOf(a to listOf(UUID(0, 1)), b to listOf(UUID(0, 2)), c to listOf(UUID(0, 3)))
        assertEquals(b, FireTeams.nextToBound(movers, heavy = setOf(UUID(0, 1), UUID(0, 3)), previous = null))
        assertEquals(c, FireTeams.nextToBound(movers, emptySet(), b))
        assertEquals(a, FireTeams.nextToBound(movers, emptySet(), c))
        assertEquals(b, FireTeams.nextToBound(movers, emptySet(), a))
        assertEquals(null, FireTeams.nextToBound(mapOf(a to listOf(UUID(0, 1))), emptySet(), a))
    }
}

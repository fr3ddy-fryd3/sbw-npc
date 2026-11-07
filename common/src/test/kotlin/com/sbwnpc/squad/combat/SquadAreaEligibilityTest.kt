package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Explicit orders stay authoritative: only holding orders and infantry are moved into a shared local area. */
class SquadAreaEligibilityTest {
    private fun squad(order: SquadOrder) = Squad(UUID.randomUUID(), "Alpha", SquadFaction.DEFAULT, order, mutableListOf(), null, null, UUID.randomUUID())

    @Test fun `defend, free patrol and retreat take a shared area`() {
        listOf(SquadOrder.DEFEND, SquadOrder.PATROL, SquadOrder.RETREAT).forEach { assertTrue(SquadAreas.eligible(squad(it)), "$it") }
    }

    @Test fun `a move keeps its exact point`() {
        assertFalse(SquadAreas.eligible(squad(SquadOrder.MOVE)))
    }

    @Test fun `a patrol on a route keeps its route`() {
        assertFalse(SquadAreas.eligible(squad(SquadOrder.PATROL).apply { routeId = UUID.randomUUID() }))
    }

    @Test fun `guarding an entity keeps its point`() {
        assertFalse(SquadAreas.eligible(squad(SquadOrder.DEFEND).apply { focusEntity = UUID.randomUUID() }))
    }

    @Test fun `vehicle crews keep their own order point`() {
        listOf(NpcClass.TANK_CREW, NpcClass.MORTAR).forEach { assertFalse(it in SquadAreas.roles, "$it") }
    }
}

package com.sbwnpc.squad.squad

import com.sbwnpc.squad.ai.coordination.TacticalOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SquadOrderTest {
    @Test fun `follow is appended so saved ordinals keep their meaning`() {
        assertEquals(listOf("DEFEND", "PATROL", "ATTACK", "MOVE", "BARRAGE", "RETREAT", "FOLLOW"), SquadOrder.entries.map { it.name })
    }

    @Test fun `only infantry can be told to follow`() {
        assertTrue(SquadOrder.FOLLOW in SquadOrder.availableFor(tank = false, mortar = false))
        assertFalse(SquadOrder.FOLLOW in SquadOrder.availableFor(tank = true, mortar = false))
        assertFalse(SquadOrder.FOLLOW in SquadOrder.availableFor(tank = false, mortar = true))
        assertFalse(SquadOrder.FOLLOW in SquadOrder.availableFor(tank = false, mortar = false, gunship = true))
        assertFalse(SquadOrder.FOLLOW in SquadOrder.availableFor(tank = false, mortar = false, transport = true))
    }

    @Test fun `follow is not a map point order`() {
        assertFalse(SquadOrder.FOLLOW in SquadOrder.pointOrdersFor(listOf(SquadOrder.availableFor(tank = false, mortar = false))))
    }

    @Test fun `follow is planned as a defence and every other order as itself`() {
        assertEquals(TacticalOrder.DEFEND, SquadOrder.FOLLOW.tactical())
        SquadOrder.entries.filter { it != SquadOrder.FOLLOW }.forEach { assertEquals(TacticalOrder.valueOf(it.name), it.tactical()) }
    }
}

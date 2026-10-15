package com.sbwnpc.squad.squad

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SquadOrderTest {
    @Test
    fun `map point commands follow squad capabilities`() {
        val tanks = SquadOrder.availableFor(tank = true, mortar = false)
        val mortars = SquadOrder.availableFor(tank = false, mortar = true)
        val infantry = SquadOrder.availableFor(tank = false, mortar = false)
        assertEquals(listOf(SquadOrder.MOVE, SquadOrder.RETREAT), SquadOrder.pointOrdersFor(listOf(tanks)))
        assertEquals(listOf(SquadOrder.ATTACK, SquadOrder.DEFEND, SquadOrder.RETREAT, SquadOrder.BARRAGE), SquadOrder.pointOrdersFor(listOf(mortars)))
        assertFalse(SquadOrder.BARRAGE in SquadOrder.pointOrdersFor(listOf(infantry)))
        assertFalse(SquadOrder.PATROL in SquadOrder.pointOrdersFor(listOf(infantry)))
        assertTrue(SquadOrder.BARRAGE in SquadOrder.pointOrdersFor(listOf(tanks, mortars)))
        assertTrue(SquadOrder.pointOrdersFor(emptyList()).isEmpty())
    }

    @Test
    fun `both helicopter classes can attack without infantry patrol commands`() {
        for (orders in listOf(
            SquadOrder.availableFor(false, false, gunship = true),
            SquadOrder.availableFor(false, false, transport = true)
        )) {
            assertTrue(SquadOrder.ATTACK in orders)
            assertFalse(SquadOrder.PATROL in orders)
            assertFalse(SquadOrder.BARRAGE in orders)
            assertEquals(listOf(SquadOrder.MOVE, SquadOrder.ATTACK, SquadOrder.DEFEND, SquadOrder.RETREAT), SquadOrder.pointOrdersFor(listOf(orders)))
        }
    }
}

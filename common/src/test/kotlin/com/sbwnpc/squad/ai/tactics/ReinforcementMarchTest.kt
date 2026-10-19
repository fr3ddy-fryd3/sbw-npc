package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReinforcementMarchTest {
    @Test fun `a distant enemy does not stop the march`() {
        // In-game 16.10: Echo stopped for an enemy 280 m away and never reached the attack.
        assertFalse(ReinforcementMarch.engaged(280.0, Long.MAX_VALUE))
        assertFalse(ReinforcementMarch.engaged(null, Long.MAX_VALUE))
    }

    @Test fun `an enemy close to the squad is its fight`() {
        assertTrue(ReinforcementMarch.engaged(48.0, Long.MAX_VALUE))
    }

    @Test fun `a squad under fire fights and marches on after 30 s without it`() {
        assertTrue(ReinforcementMarch.engaged(280.0, 0))
        assertTrue(ReinforcementMarch.engaged(280.0, 599))
        assertFalse(ReinforcementMarch.engaged(280.0, 600))
    }

    @Test fun `a fighting squad marches on only once the enemy has drawn off`() {
        // In-game 16.10: an enemy at 49, 32, 52, 25 blocks flipped the squad every few seconds.
        assertTrue(ReinforcementMarch.engaged(52.0, Long.MAX_VALUE, fighting = true))
        assertFalse(ReinforcementMarch.engaged(52.0, Long.MAX_VALUE, fighting = false))
        assertFalse(ReinforcementMarch.engaged(65.0, Long.MAX_VALUE, fighting = true))
    }

    @Test fun `the nearest contact is measured from the nearest man, height included`() {
        val men = listOf(Point3(0.0, 64.0, 0.0), Point3(100.0, 64.0, 0.0))
        val contacts = listOf(Point3(130.0, 64.0, 0.0), Point3(0.0, 104.0, 0.0))
        assertEquals(30.0, ReinforcementMarch.nearestContact(contacts, men))
        assertEquals(40.0, ReinforcementMarch.nearestContact(listOf(Point3(0.0, 104.0, 0.0)), men))
    }

    @Test fun `without contacts there is no nearest one`() {
        assertNull(ReinforcementMarch.nearestContact(emptyList(), listOf(Point3(0.0, 64.0, 0.0))))
        assertEquals(Double.MAX_VALUE, ReinforcementMarch.nearestContact(listOf(Point3(0.0, 64.0, 0.0)), emptyList()))
    }
}

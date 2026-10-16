package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertFalse
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
}

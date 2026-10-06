package com.sbwnpc.squad.combat.tactics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalPositionScoreTest {
    @Test fun `shelter ranks before exposure and movement cost`() {
        val nearbyOpen = TacticalPositionScore(true, 3, 0.0)
        val sheltered = TacticalPositionScore(false, 0, 30.0)
        assertTrue(sheltered < nearbyOpen)
    }

    @Test fun `lower exposure ranks before movement cost when shelter is equal`() {
        assertTrue(TacticalPositionScore(true, 1, 20.0) < TacticalPositionScore(true, 3, 0.0))
    }

    @Test fun `lower movement cost breaks equal shelter and exposure scores`() {
        val current = TacticalPositionScore(true, 3, 4.0)
        val shifted = TacticalPositionScore(true, 3, 8.0)
        assertTrue(current < shifted)
        assertTrue(shifted > current)
        assertEquals(0, current.compareTo(current.copy()))
    }
}

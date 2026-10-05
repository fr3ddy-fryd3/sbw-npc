package com.sbwnpc.squad.combat.tactics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalPositionScoreTest {
    @Test fun `reachable shelter wins over a short exposed walk`() {
        val nearbyOpen = TacticalPositionScore(true, 3, 0.0)
        val sheltered = TacticalPositionScore(false, 0, 30.0)
        assertTrue(sheltered < nearbyOpen)
        assertEquals(sheltered, listOf(nearbyOpen, sheltered).minOrNull())
    }

    @Test fun `partial shelter from the enemy group wins over exposure to every shooter`() {
        assertTrue(TacticalPositionScore(true, 1, 20.0) < TacticalPositionScore(true, 3, 0.0))
    }

    @Test fun `open field retains a stable firing post when no safer candidate exists`() {
        val current = TacticalPositionScore(true, 3, 4.0)
        val shifted = TacticalPositionScore(true, 3, 8.0)
        assertEquals(current, listOf(current, shifted).minOrNull())
        assertEquals(current, listOf(current).minOrNull())
    }
}

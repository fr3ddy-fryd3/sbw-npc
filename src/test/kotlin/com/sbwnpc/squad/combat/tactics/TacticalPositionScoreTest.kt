package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalPositionScoreTest {
    @Test fun `a covered flank prefers progress and a necessary sideways step over retreat`() {
        val origin = Vec3.ZERO
        val goal = Vec3(64.0, 0.0, 0.0)
        val forward = TacticalPositionScore.coveredFlankStep(origin, goal, Vec3(24.0, 0.0, 0.0))
        val sideways = TacticalPositionScore.coveredFlankStep(origin, goal, Vec3(0.0, 0.0, 24.0))
        val backward = TacticalPositionScore.coveredFlankStep(origin, goal, Vec3(-8.0, 0.0, 0.0))
        assertTrue(forward < sideways)
        assertTrue(sideways < backward)
    }

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

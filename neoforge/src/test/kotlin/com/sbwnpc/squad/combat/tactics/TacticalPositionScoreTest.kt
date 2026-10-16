package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalPositionScoreTest {
    @Test fun `a native transit batch excludes shorter routes that sacrifice the chosen progress`() {
        val goal = Vec3(12.0, 0.0, 0.0)
        val best = TacticalPositionScore.coveredTransitStep(Vec3.ZERO, goal, goal)
        assertTrue(best.sameRouteTier(TacticalPositionScore.coveredTransitStep(Vec3.ZERO, goal, Vec3(11.0, 0.0, 0.0))))
        assertFalse(best.sameRouteTier(TacticalPositionScore.coveredTransitStep(Vec3.ZERO, goal, Vec3(4.0, 0.0, 0.0))))
        // Ordinary firing-post batches still group by actual shelter and exposure.
        assertTrue(TacticalPositionScore(false, 0, 1.0).sameRouteTier(TacticalPositionScore(false, 0, 20.0)))
        assertFalse(best.sameRouteTier(TacticalPositionScore(true, 0, 0.0)))
    }

    @Test fun `a covered flank prefers progress and a necessary sideways step over retreat`() {
        val origin = Vec3.ZERO
        val goal = Vec3(64.0, 0.0, 0.0)
        val forward = TacticalPositionScore.coveredTransitStep(origin, goal, Vec3(24.0, 0.0, 0.0))
        val sideways = TacticalPositionScore.coveredTransitStep(origin, goal, Vec3(0.0, 0.0, 24.0))
        val backward = TacticalPositionScore.coveredTransitStep(origin, goal, Vec3(-8.0, 0.0, 0.0))
        assertTrue(forward < sideways)
        assertTrue(sideways < backward)
    }

    @Test fun `straight deployment favors approaching the goal over standing anywhere along its route`() {
        val origin = Vec3.ZERO
        val goal = Vec3(12.0, 0.0, 0.0)
        val idle = TacticalPositionScore.coveredTransitStep(origin, goal, origin)
        val middle = TacticalPositionScore.coveredTransitStep(origin, goal, Vec3(6.0, 0.0, 0.0))
        val arrived = TacticalPositionScore.coveredTransitStep(origin, goal, goal)
        assertTrue(middle < idle)
        assertTrue(arrived < middle)
        // A catch-up assignment can lead backward; explicit movement still follows its goal.
        val catchingUp = TacticalPositionScore.coveredTransitStep(goal, origin, Vec3(6.0, 0.0, 0.0))
        assertTrue(catchingUp < TacticalPositionScore.coveredTransitStep(goal, origin, goal))
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

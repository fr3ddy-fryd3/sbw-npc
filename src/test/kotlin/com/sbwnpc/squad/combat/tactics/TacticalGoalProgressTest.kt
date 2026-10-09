package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalGoalProgressTest {
    @Test fun `advance accepts progress but rejects an already reached or retreating intermediate post`() {
        val task = TacticalTask(TacticalJob.ADVANCE, Vec3(20.0, 10.0, 0.0), null, 1)
        assertFalse(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3.ZERO, deploying = false))
        assertFalse(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3(-4.0, 0.0, 0.0), deploying = false))
        assertTrue(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3(4.0, 1.0, 0.0), deploying = false))
        assertFalse(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3.ZERO, deploying = true))
        assertTrue(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3(4.0, 1.0, 0.0), deploying = true))
        assertTrue(TacticalGoalProgress.permitsAdvance(TacticalTask(TacticalJob.COVER, task.anchor, null, 1), Vec3.ZERO, Vec3.ZERO, deploying = false))
    }

    @Test fun `being near an advance anchor cannot settle deployment outside its owned lane`() {
        val goal = Vec3.ZERO
        val task = TacticalTask(TacticalJob.ADVANCE, goal, null, 1).apply {
            frontage = TacticalFrontPost(goal, Vec3(1.0, 0.0, 0.0))
        }
        val outside = Vec3(0.0, 0.0, 7.0)
        assertTrue(TacticalGoalProgress.reached(task, outside))
        assertFalse(TacticalGoalProgress.permitsAdvance(task, outside, outside, deploying = true))
        assertTrue(TacticalGoalProgress.permitsAdvance(task, outside, Vec3(0.0, 0.0, 3.0), deploying = true))
        assertTrue(TacticalGoalProgress.permitsAdvance(task, goal, goal, deploying = false))
    }

    @Test fun `arrival tolerance permits holding exactly at its boundary but not beyond it`() {
        val origin = Vec3.ZERO
        val atBoundary = TacticalTask(TacticalJob.ADVANCE, Vec3(0.0, 10.0, 0.0), null, 1)
        val beyond = TacticalTask(TacticalJob.ADVANCE, Vec3(0.0, 10.001, 0.0), null, 1)
        assertTrue(TacticalGoalProgress.permitsAdvance(atBoundary, origin, origin, deploying = false))
        assertFalse(TacticalGoalProgress.permitsAdvance(beyond, origin, origin, deploying = false))
    }
}

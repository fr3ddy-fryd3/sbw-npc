package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalGoalProgressTest {
    @Test fun `advance accepts progress but rejects an already reached or retreating intermediate post`() {
        val task = TacticalTask(TacticalJob.ADVANCE, Vec3(20.0, 10.0, 0.0), null, 1)
        assertFalse(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3.ZERO))
        assertFalse(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3(-4.0, 0.0, 0.0)))
        assertTrue(TacticalGoalProgress.permitsAdvance(task, Vec3.ZERO, Vec3(4.0, 1.0, 0.0)))
        assertTrue(TacticalGoalProgress.permitsAdvance(TacticalTask(TacticalJob.COVER, task.anchor, null, 1), Vec3.ZERO, Vec3.ZERO))
    }

    @Test fun `arrival tolerance permits holding exactly at its boundary but not beyond it`() {
        val origin = Vec3.ZERO
        val atBoundary = TacticalTask(TacticalJob.ADVANCE, Vec3(0.0, 10.0, 0.0), null, 1)
        val beyond = TacticalTask(TacticalJob.ADVANCE, Vec3(0.0, 10.001, 0.0), null, 1)
        assertTrue(TacticalGoalProgress.permitsAdvance(atBoundary, origin, origin))
        assertFalse(TacticalGoalProgress.permitsAdvance(beyond, origin, origin))
    }
}

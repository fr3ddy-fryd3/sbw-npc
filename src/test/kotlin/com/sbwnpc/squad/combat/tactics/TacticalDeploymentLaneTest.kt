package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Query-stage policy only: navigation and live weapon execution are not simulated here. */
class TacticalDeploymentLaneTest {
    @Test fun `reaching the assigned lane does not require a firing lane but choosing a final post does`() {
        val post = Vec3(24.5, 64.0, 0.5)
        for (job in listOf(TacticalJob.COVER, TacticalJob.WAIT)) {
            val task = TacticalTask(job, post, Vec3(100.5, 64.0, 0.5), 1).also {
                it.frontage = TacticalFrontPost(post, Vec3(1.0, 0.0, 0.0))
            }
            assertTrue(task.deployingFront(Vec3(0.5, 64.0, 0.5)))
            assertTrue(task.permitsFront(post))
            assertFalse(TacticalPositions.requiresFiringLane(task, deploying = true, post))
            assertTrue(TacticalPositions.requiresFiringLane(task, deploying = false, post))
        }
    }
}

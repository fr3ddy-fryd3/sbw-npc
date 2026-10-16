package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Query-stage policy only: navigation and live weapon execution are not simulated here. */
class TacticalDeploymentLaneTest {
    @Test fun `a blind transit leg is allowed but a post already in the owned strip checks its shot`() {
        val post = Vec3(24.5, 64.0, 0.5)
        for (job in listOf(TacticalJob.COVER, TacticalJob.WAIT)) {
            val task = TacticalTask(job, post, Vec3(100.5, 64.0, 0.5), 1).also {
                it.frontage = TacticalFrontPost(post, Vec3(1.0, 0.0, 0.0))
            }
            assertTrue(task.deployingFront(Vec3(0.5, 64.0, 0.5)))
            assertTrue(task.permitsFront(post))
            assertFalse(TacticalPositions.requiresFiringLane(task, deploying = true, Vec3(0.5, 64.0, 0.5)))
            assertTrue(TacticalPositions.requiresFiringLane(task, deploying = true, post))
            assertTrue(TacticalPositions.requiresFiringLane(task, deploying = true, post.add(-4.0, 0.0, 0.0)))
            assertTrue(TacticalPositions.requiresFiringLane(task, deploying = false, post))
        }
    }
}

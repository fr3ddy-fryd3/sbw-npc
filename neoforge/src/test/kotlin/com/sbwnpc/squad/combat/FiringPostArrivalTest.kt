package com.sbwnpc.squad.combat

import com.sbwnpc.squad.combat.tactics.*
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FiringPostArrivalTest {
    private val post = Vec3(0.5, 64.0, 0.5)

    @Test fun `stopping one or two blocks before cover is not arrival`() {
        assertFalse(FiringPostArrival.reached(post.add(2.0, 0.0, 0.0), post))
        assertFalse(FiringPostArrival.reached(post.add(1.0, 0.0, 0.0), post))
        assertTrue(FiringPostArrival.reached(post.add(0.3, 0.0, 0.0), post))
        assertTrue(FiringPostArrival.reached(post.add(0.0, 0.5, 0.0), post))
        assertFalse(FiringPostArrival.reached(post.add(0.0, 1.0, 0.0), post))
    }

    @Test fun `covering members cannot confirm cover from an adjacent exposed block`() {
        for (job in listOf(TacticalJob.COVER, TacticalJob.WAIT, TacticalJob.OVERWATCH)) {
            val task = TacticalTask(job, post, null, 1).also { it.position = post }
            val member = TacticalMember(UUID(0, 1), post.add(1.0, 0.0, 0.0), canFire = true)
            assertFalse(TacticalEvidence.settled(member, task), job.toString())
            assertTrue(TacticalEvidence.settled(member.copy(position = post), task))
        }
    }
}

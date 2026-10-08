package com.sbwnpc.squad.combat

import com.sbwnpc.squad.combat.tactics.*
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FiringPostArrivalTest {
    private val post = Vec3(0.5, 64.0, 0.5)

    @Test fun `lost sight during the final approach does not repeatedly restart the firing route`() {
        val task = TacticalTask(TacticalJob.WAIT, post, Vec3(100.0, 64.0, 0.5), 1).apply {
            position = post
            lastProgress = 20
            nextSearch = 120
            search = TacticalPositionSearch(post, listOf(post)).apply { probeIndex = 1 }
        }
        val search = task.search
        for (tick in listOf(40L, 80L, 120L, 160L)) {
            assertFalse(TacticalMovement.invalidateReachedFirePost(task, post.add(0.8, -1.0, 1.4), grounded = true, tick))
            assertFalse(TacticalMovement.invalidateReachedFirePost(task, post.add(0.1, -0.3, 0.1), grounded = false, tick))
            assertEquals(post, task.position)
            assertSame(search, task.search)
            assertEquals(20, task.lastProgress)
            assertEquals(120, task.nextSearch)
        }
        assertTrue(TacticalMovement.invalidateReachedFirePost(task, post, grounded = true, 200))
        assertNull(task.position)
        assertNull(task.search)
        assertEquals(210, task.nextSearch)
    }

    @Test fun `stopping one or two blocks before cover is not arrival`() {
        assertFalse(FiringPostArrival.reached(post.add(2.0, 0.0, 0.0), post))
        assertFalse(FiringPostArrival.reached(post.add(1.0, 0.0, 0.0), post))
        assertTrue(FiringPostArrival.reached(post.add(0.3, 0.0, 0.0), post))
        assertTrue(FiringPostArrival.reached(post.add(0.0, 0.5, 0.0), post))
        assertFalse(FiringPostArrival.reached(post.add(0.0, 1.0, 0.0), post))
    }

    @Test fun `flankers and covering members cannot confirm cover from an adjacent exposed block`() {
        for (job in listOf(TacticalJob.COVER, TacticalJob.WAIT, TacticalJob.OVERWATCH, TacticalJob.FLANK)) {
            val task = TacticalTask(job, post, null, 1).also { it.position = post }
            val member = TacticalMember(UUID(0, 1), post.add(1.0, 0.0, 0.0), canFire = true)
            assertFalse(TacticalEvidence.settled(member, task), job.toString())
            assertTrue(TacticalEvidence.settled(member.copy(position = post), task))
        }
    }
}

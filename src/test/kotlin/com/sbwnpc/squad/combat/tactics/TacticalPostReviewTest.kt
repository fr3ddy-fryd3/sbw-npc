package com.sbwnpc.squad.combat.tactics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalPostReviewTest {
    private val post = Vec3(10.0, 64.0, 0.0)
    private val first = UUID(1, 1)
    private val second = UUID(1, 2)

    private fun task() =
        TacticalTask(TacticalJob.COVER, post, Vec3(80.0, 64.0, 0.0), 1).apply {
            position = post
            firingTarget = first
            search = TacticalPositionSearch(Vec3.ZERO, listOf(post))
            navigationPath = Path(listOf(Node(10, 64, 0)), BlockPos.containing(post), true)
            closest = 5.0
            lastProgress = 10
            nextSearch = 30
        }

    @Test fun `switching between shootable targets preserves the post path and movement progress`() {
        val task = task()
        val path = task.navigationPath
        assertEquals(TacticalPostReview.Result.RETAINED, TacticalPostReview.review(task, second, 20) { true })
        assertSame(post, task.position)
        assertSame(path, task.navigationPath)
        assertEquals(5.0, task.closest)
        assertEquals(10L, task.lastProgress)
        assertEquals(30L, task.nextSearch)
        assertEquals(second, task.firingTarget)
        assertNull(task.search)
        assertEquals(60L, task.nextValidation)
        assertEquals(TacticalPostReview.Result.UNCHANGED, TacticalPostReview.review(task, second, 21) { fail("The same target needs no new raycasts") })
    }

    @Test fun `a blocked new target requests replacement while budget exhaustion keeps the old work`() {
        val task = task()
        val path = task.navigationPath
        val search = task.search
        assertEquals(TacticalPostReview.Result.DEFERRED, TacticalPostReview.review(task, second, 20) { null })
        assertEquals(first, task.firingTarget)
        assertSame(post, task.position)
        assertSame(path, task.navigationPath)
        assertSame(search, task.search)
        assertEquals(TacticalPostReview.Result.INVALIDATED, TacticalPostReview.review(task, second, 21) { false })
        assertEquals(first, task.firingTarget)
        assertEquals(10L, task.lastProgress)
    }

    @Test fun `losing a target preserves the post until a new target can be checked`() {
        val task = task()
        val path = task.navigationPath
        assertEquals(TacticalPostReview.Result.RETAINED, TacticalPostReview.review(task, null, 20) { fail("No target to validate") })
        assertNull(task.firingTarget)
        assertSame(path, task.navigationPath)
        assertSame(post, task.position)
        assertEquals(TacticalPostReview.Result.INVALIDATED, TacticalPostReview.review(task, second, 30) { false })
    }

    @Test fun `target changes do not cancel the routes of moving groups`() {
        val task = TacticalTask(TacticalJob.FLANK, post, post, 1).apply {
            position = post
            firingTarget = first
        }
        assertEquals(TacticalPostReview.Result.UNCHANGED, TacticalPostReview.review(task, second, 20) { fail("A flank route is independent of the gun target") })
        assertSame(post, task.position)
    }

    @Test fun `first contact releases the march route once and preserves subsequent owned and preempting paths`() {
        fun path() = Path(listOf(Node(10, 64, 0)), BlockPos.containing(post), true)
        val contact = TacticalTask(TacticalJob.COVER, post, post, 1).apply { coverOrigin = post }
        var stopped = 0
        assertTrue(contact.beginDeployment(path()) { stopped++ })
        val owned = path()
        contact.navigationPath = owned
        assertFalse(contact.beginDeployment(owned) { stopped++ })
        assertFalse(contact.beginDeployment(path()) { stopped++ })
        val waiting = contact.withJob(TacticalJob.WAIT, post)
        assertSame(owned, waiting.navigationPath)
        assertFalse(waiting.beginDeployment(owned) { stopped++ })
        assertFalse(waiting.finishDeployment(TacticalPhase.PREPARING))
        assertFalse(waiting.finishDeployment(TacticalPhase.OPENING_LANE))
        assertEquals(post, waiting.coverOrigin, "The first formation must keep its contact constraints while still preparing")
        assertTrue(waiting.finishDeployment(TacticalPhase.EXECUTING))
        assertFalse(waiting.finishDeployment(TacticalPhase.EXECUTING))
        assertNull(waiting.coverOrigin)
        assertSame(owned, waiting.navigationPath, "Completing deployment must not release its owned route")
        assertNull(waiting.withJob(TacticalJob.COVER, post).coverOrigin, "Later role exchanges must not restart contact deployment")
        assertEquals(1, stopped)
        val alreadyOwned = TacticalTask(TacticalJob.COVER, post, post, 1).apply {
            coverOrigin = post
            navigationPath = owned
        }
        assertFalse(alreadyOwned.beginDeployment(owned) { stopped++ })
        assertSame(owned, alreadyOwned.navigationPath)
        for (job in listOf(TacticalJob.FLANK, TacticalJob.RESERVE, TacticalJob.RETREAT)) {
            assertFalse(TacticalTask(job, post, post, 1).apply { coverOrigin = post }.beginDeployment(path()) { stopped++ })
        }
        assertFalse(TacticalTask(TacticalJob.COVER, post, post, 1).beginDeployment(path()) { stopped++ })
        assertEquals(1, stopped, "Defence, role work and withdrawal must retain their existing navigation")
    }
}

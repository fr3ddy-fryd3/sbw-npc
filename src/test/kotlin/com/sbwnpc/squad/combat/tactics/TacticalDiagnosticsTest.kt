package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalDiagnosticsTest {
    private val squad = UUID(0, 7)
    private val origin = Vec3(0.0, 64.0, 0.0)
    private val focus = Vec3(60.0, 64.0, 0.0)

    @Test fun `a lost covering group produces distinct pause and failure logs with evidence from before cleanup`() {
        val events = mutableListOf<TacticalEvent>()
        val members = (0..5).map { TacticalMember(UUID(0, it.toLong()), origin, canFire = true) }
        fun view(now: Long, ready: Boolean) = TacticalSnapshot(now, SquadOrder.ATTACK, 1, origin, focus,
            members.map { it.copy(canFire = ready, recentFire = false) }, listOf(TacticalContact(UUID(1, 1), focus, now)))
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.BOUND, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, view(0, true))
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position = origin }
        TacticalCoordinator.advance(squad, state, plan, view(10, true))
        val started = events.filterIsInstance<TacticalEvent.Transition>().last()
        assertEquals(3, started.evidence.coverReady)
        assertEquals(0, started.evidence.coverFired)
        TacticalCoordinator.advance(squad, state, plan, view(110, false))
        val paused = events.filterIsInstance<TacticalEvent.Transition>().last()
        assertEquals(TacticalPhase.PAUSED, plan.phase)
        val pauseLog = TacticalEventFormat.detail(paused)
        assertTrue(pauseLog.contains("to=PAUSED"))
        assertTrue(pauseLog.contains("trigger=cover_lost_pause"))
        assertTrue(pauseLog.contains("coverReady=0"))
        assertTrue(pauseLog.contains("movers=3"))
        TacticalCoordinator.advance(squad, state, plan, view(270, false))
        val failed = events.filterIsInstance<TacticalEvent.Transition>().last()
        assertEquals(TacticalPhase.FAILED, plan.phase)
        assertTrue(plan.tasks.isEmpty())
        assertEquals(3, failed.evidence.coverAssigned, "Failure evidence must be captured before assignment cleanup")
        assertTrue(TacticalEventFormat.detail(failed).contains("trigger=COVER_LOST"))
        assertEquals(6, events.filterIsInstance<TacticalEvent.TaskReleased>().count { it.trigger == "COVER_LOST" })
    }

    @Test fun `individual preemption logs changes without duplicating squad pauses or disturbing execution progress`() {
        val events = mutableListOf<TacticalEvent>()
        val sink = TacticalEventSink(events::add)
        val task = TacticalTask(TacticalJob.FLANK, focus, focus, 1).also {
            it.position = origin
            it.lastProgress = 12
            it.nextSearch = 30
            it.suspend(20, "cover_lost")
        }
        val member = UUID(0, 1)
        for (now in 20L..60L) task.observeBlocker(member, now, TacticalMovementBlocker.COVER, sink)
        task.observeBlocker(member, 70, TacticalMovementBlocker.MEDIC, sink)
        task.observeBlocker(member, 80, null, sink)
        task.observeBlocker(member, 90, null, sink)
        val blocks = events.filterIsInstance<TacticalEvent.MovementBlocked>()
        assertEquals(3, blocks.size)
        assertEquals(TacticalMovementBlocker.COVER, blocks[1].previous)
        assertEquals(TacticalMovementBlocker.MEDIC, blocks[1].current)
        assertTrue(TacticalEventFormat.detail(blocks.last()).contains("event=movement_unblocked"))
        assertEquals(12L, task.lastProgress)
        assertEquals(30L, task.nextSearch)
        assertEquals(20L, task.pausedAt)
        assertEquals(origin, task.position)
        assertTrue(events.none { it is TacticalEvent.TaskPaused })
    }
}

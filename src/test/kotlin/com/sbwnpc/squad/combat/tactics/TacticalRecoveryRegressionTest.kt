package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalRecoveryRegressionTest {
    private val focus = Vec3(100.0, 64.0, 0.0)
    private fun view(now: Long = 0) =
        TacticalSnapshot(
            now,
            SquadOrder.ATTACK,
            1,
            Vec3(0.0, 64.0, 0.0),
            focus,
            (0..31).map { TacticalMember(UUID(0, it.toLong()), Vec3(0.0, 64.0, (it - 15.5) * 6.0), canFire = false, recentFire = false) },
            listOf(TacticalContact(UUID(1, 1), focus, now)),
        )

    @Test fun `cover and wait exchanges preserve unfinished fire recovery and its owned path`() {
        for (desired in listOf(TacticalJob.COVER, TacticalJob.WAIT)) {
            val plan = TacticalPlan(1, TacticalPattern.FLANK, focus, 0, 1)
            val initial = view()
            val squad = UUID(2, 2)
            TacticalCoordinator.assign(squad, plan, initial)
            val (id, original) = plan.tasks.entries.first { it.value.job == desired }
            val previous = original.withJob(if (desired == TacticalJob.COVER) TacticalJob.WAIT else TacticalJob.COVER, original.anchor)
            previous.localFirePost = initial.members.first { it.id == id }.position
            previous.position = previous.localFirePost!!.add(0.0, 0.0, 4.0)
            previous.search = TacticalPositionSearch(previous.localFirePost!!, listOf(previous.position!!)).apply { probeIndex = 1 }
            previous.nextSearch = 65
            previous.lastProgress = 20
            previous.closest = 4.0
            previous.navigationPath = net.minecraft.world.level.pathfinder.Path(mutableListOf(), net.minecraft.core.BlockPos.ZERO, true)
            val path = previous.navigationPath
            plan.assignments.put(id, previous, 10, "support_role_changed")
            plan.behavior.assign(TacticalContext(squad, plan, initial.copy(now = 30)), retainCover = true, trigger = "next_movement_group")
            val retained = plan.tasks.getValue(id)
            assertEquals(desired, retained.job)
            assertEquals(previous.position, retained.position)
            assertSame(previous.search, retained.search)
            assertSame(path, retained.navigationPath, "The old task must release ownership without stopping the transferred path")
            assertEquals(65, retained.nextSearch)
            assertEquals(20, retained.lastProgress)
            assertEquals(4.0, retained.closest)
        }
    }

    @Test fun `a phase assignment keeps an ongoing cover recovery when line height changes`() {
        val events = mutableListOf<TacticalEvent>()
        val plan = TacticalPlan(1, TacticalPattern.FLANK, focus, 0, 1, events = TacticalEventSink(events::add))
        val initial = view()
        val squad = UUID(2, 2)
        TacticalCoordinator.assign(squad, plan, initial)
        val (id, task) = plan.tasks.entries.first { it.value.job == TacticalJob.COVER }
        val member = initial.members.first { it.id == id }
        task.localFirePost = member.position
        task.expandedFireSearch = true
        task.position = member.position.add(0.0, 0.0, 4.0)
        task.lastProgress = 20
        val next = initial.copy(now = 30, center = initial.center.add(0.0, 0.25, 0.0))
        (plan.behavior as CoveredManeuverState).advanceFront(TacticalContext(squad, plan, next), next.center)
        plan.behavior.assign(TacticalContext(squad, plan, next), retainCover = true, trigger = "movement_started")
        assertSame(task, plan.tasks[id], "Another wave starting must not cancel this shooter's local route")
        assertTrue(events.filterIsInstance<TacticalEvent.TaskReleased>().none { it.member == id })
        task.position = null
        task.search = TacticalPositionSearch(member.position, listOf(member.position.add(0.0, 0.0, 8.0))).apply { probeIndex = 1 }
        plan.behavior.assign(TacticalContext(squad, plan, next.copy(now = 40)), retainCover = true, trigger = "next_movement_group")
        assertSame(task, plan.tasks[id], "Budgeted post search must continue across the phase")
        assertEquals(1, task.search!!.probeIndex)
    }

    @Test fun `late support exchanges only the two roles and preserves every other task`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(UUID(2, 2), plan, view())
        val old = plan.tasks.toMap()
        val shooter = old.entries.first { it.value.job == TacticalJob.WAIT }.key
        val next = view(20).copy(
            members = view().members.map {
                if (it.id == shooter) it.copy(canFire = true, recentFire = true, firingAt = focus, recentFireAt = focus) else it
            },
        )
        events.clear()
        TacticalCoordinator.advance(UUID(2, 2), state, plan, next)
        assertEquals(TacticalJob.COVER, plan.tasks.getValue(shooter).job)
        val replaced = events.filterIsInstance<TacticalEvent.TaskAssigned>().map { it.member }.toSet()
        assertEquals(2, replaced.size)
        old.filterKeys { it !in replaced }.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        events.clear()
        TacticalCoordinator.advance(UUID(2, 2), state, plan, next.copy(now = 30))
        assertTrue(events.none { it is TacticalEvent.TaskReleased })
    }

    @Test fun `probe travel under incoming fire is shorter and preserves lateral ownership`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        val view = view()
        TacticalCoordinator.assign(UUID(2, 2), plan, view)
        val context = TacticalContext(UUID(2, 2), plan, view, state)
        val member = view.members[10]
        val quiet = TacticalLaneRecovery.probe(context, member)
        val pressured = TacticalLaneRecovery.probe(context.copy(view = view.copy(incoming = listOf(focus))), member)
        assertEquals(12.0, quiet.probeAdvance)
        assertEquals(4.0, pressured.probeAdvance)
        assertEquals(8.0, quiet.anchor.subtract(pressured.anchor).dot(plan.frontage.forward), 0.001)
        assertEquals(0.0, quiet.anchor.subtract(pressured.anchor).dot(plan.frontage.side), 0.001)
        assertTrue(quiet.opensLane)
        assertFalse(quiet.maneuverPost, "An approach waypoint is not evidence of a firing position")
        val alreadyAhead = member.copy(position = member.position.add(plan.frontage.forward.scale(15.0)))
        val next = TacticalLaneRecovery.probe(context, alreadyAhead)
        assertTrue(
            next.anchor.subtract(alreadyAhead.position).dot(plan.frontage.forward) >= 12.0 - 0.001,
            "A scout already ahead of the nominal front must not be pulled backward",
        )
    }

    @Test fun `existing cover cannot cancel a blocked lane probe before it moves`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(UUID(2, 2), plan, view())
        val deployed = view(100).assumingFrontArrival(plan)
        TacticalCoordinator.advance(UUID(2, 2), state, plan, deployed)
        assertEquals(TacticalPhase.OPENING_LANE, plan.phase)
        val probes = plan.tasks.filterValues { it.opensLane }.toMap()
        val oldShooters = plan.tasks.filterValues { it.job == TacticalJob.WAIT }.keys.take(2)
        val covering = deployed.copy(
            now = 101,
            members = deployed.members.map {
                if (it.id in oldShooters) it.copy(canFire = true, recentFire = true, firingAt = focus, recentFireAt = focus) else it
            },
        )
        TacticalCoordinator.advance(UUID(2, 2), state, plan, covering)
        assertEquals(TacticalPhase.OPENING_LANE, plan.phase)
        probes.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
    }

    @Test fun `rejected flank movers cannot be reported as completed`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        val initial = view().copy(
            members = view().members.map {
                it.copy(canFire = true, recentFire = true, firingAt = focus, recentFireAt = focus)
            },
        )
        TacticalCoordinator.assign(UUID(2, 2), plan, initial)
        TacticalCoordinator.advance(UUID(2, 2), state, plan, initial.assumingFrontArrival(plan))
        val flank = plan.behavior as FlankingState
        flank.order.toList().forEach { TacticalCoordinator.abandon(plan, it) }
        TacticalCoordinator.advance(UUID(2, 2), state, plan, initial.copy(now = 40).assumingFrontArrival(plan))
        assertEquals(TacticalStatus.FAILED, plan.status)
        assertEquals(TacticalFailure.PARTIAL_MANEUVER, plan.failure)
    }

    @Test fun `failed firing strip expands only the affected post and keeps bounded ownership`() {
        val task = TacticalTask(TacticalJob.WAIT, Vec3.ZERO, focus, 1).apply {
            frontage = TacticalFrontPost(Vec3(12.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        }
        val aroundBerm = Vec3(6.0, 3.0, 5.0)
        assertTrue(TacticalFirePosts.recoverAfterSearch(task, Vec3.ZERO))
        assertFalse(TacticalFirePosts.recoverAfterSearch(task, Vec3(37.0, 0.0, 0.0)))
        assertFalse(TacticalFirePosts.recoverAfterSearch(task.withJob(TacticalJob.SEARCH, task.anchor), Vec3.ZERO))
        assertTrue(TacticalFirePosts.recover(task, Vec3.ZERO))
        assertFalse(task.permitsFront(aroundBerm))
        assertTrue(TacticalFirePosts.expand(task, Vec3.ZERO))
        assertEquals(Vec3.ZERO, task.localFirePost)
        assertTrue(task.permitsFront(aroundBerm))
        assertFalse(task.permitsFront(Vec3(25.0, 0.0, 0.0)))
        task.position = aroundBerm
        assertTrue(TacticalEvidence.settled(TacticalMember(UUID(1, 2), aroundBerm), task))
        assertTrue(TacticalPositions.requiresFiringLane(task, deploying = false, aroundBerm))
        assertEquals(TacticalJob.WAIT, task.job)
        assertEquals(focus, task.focus)
        assertFalse(TacticalFirePosts.expand(task, aroundBerm), "The fixed radius must not grow on later failures")
        assertEquals(Vec3.ZERO, task.localFirePost, "Retries must not move the escape origin with the actor")
    }

    @Test fun `probe shortens its approach before the nearest reported enemy`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        val view = view().copy(center = Vec3(44.0, 64.0, 0.0), members = view().members.map { it.copy(position = it.position.add(44.0, 0.0, 0.0)) })
        TacticalCoordinator.assign(UUID(2, 2), plan, view)
        val member = view.members[15]
        val task = TacticalLaneRecovery.probe(TacticalContext(UUID(2, 2), plan, view, state), member)
        assertTrue(task.anchor.subtract(member.position).dot(plan.frontage.forward) in 1.0..7.0)
        assertTrue(task.anchor.subtract(focus).horizontalDistance() >= 49.9)
        assertTrue(TacticalRoutes.safeLaneProbe(member.position, task.anchor, focus, task.probeAdvance))
    }
}

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
            frontage = TacticalFrontPost(Vec3.ZERO, Vec3(1.0, 0.0, 0.0))
        }
        val aroundBerm = Vec3(6.0, 3.0, 5.0)
        assertTrue(TacticalFirePosts.recoverAfterSearch(task, Vec3.ZERO))
        assertFalse(TacticalFirePosts.recoverAfterSearch(task, Vec3(25.0, 0.0, 0.0)))
        assertFalse(TacticalFirePosts.recoverAfterSearch(task.withJob(TacticalJob.SEARCH, task.anchor), Vec3.ZERO))
        assertTrue(TacticalFirePosts.recover(task, Vec3.ZERO))
        assertFalse(task.permitsFront(aroundBerm))
        assertTrue(TacticalFirePosts.expand(task))
        assertTrue(task.permitsFront(aroundBerm))
        assertFalse(task.permitsFront(Vec3(25.0, 0.0, 0.0)))
        task.position = aroundBerm
        assertTrue(TacticalEvidence.settled(TacticalMember(UUID(1, 2), aroundBerm), task))
        assertTrue(TacticalPositions.requiresFiringLane(task, deploying = false, aroundBerm))
        assertEquals(TacticalJob.WAIT, task.job)
        assertEquals(focus, task.focus)
        assertFalse(TacticalFirePosts.expand(task), "The fixed radius must not grow on later failures")
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

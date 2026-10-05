package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalAttackRegressionTest {
    private val squad = UUID(5, 1)
    private val focus = Vec3(80.0, 64.0, 0.0)
    private val members = (0..31).map {
        TacticalMember(UUID(0, it.toLong()), Vec3(0.0, 64.0, it * 4.0), canFire = true, firingAt = focus, recentFireAt = focus)
    }
    private fun view(now: Long = 0) =
        TacticalSnapshot(
            now,
            SquadOrder.ATTACK,
            1,
            Vec3(0.0, 64.0, 0.0),
            focus,
            members,
            listOf(TacticalContact(UUID(9, 1), focus, now)),
        )

    @Test fun `a reported enemy activates distant squad members without a maneuver assignment`() {
        val contact = view().contacts.single()
        for (distance in listOf(120.0, 300.0, 1500.0)) {
            val receiver = focus.add(distance, 0.0, 0.0)
            assertEquals(listOf(contact), TacticalTargets.candidates(view(), null, receiver, false))
        }
        assertTrue(TacticalTargets.candidates(view(201).copy(contacts = listOf(contact)), null, Vec3.ZERO, false).isEmpty())
    }

    @Test fun `blocked coordinated fire posts retain the sector and cannot become an assault task`() {
        val task = TacticalTask(TacticalJob.WAIT, focus.add(48.0, 0.0, 0.0), focus, 1)
        val post = Vec3(0.0, 64.0, 0.0)
        task.staging = post
        assertTrue(TacticalFirePosts.recover(task, post))
        assertEquals(TacticalJob.WAIT, task.job)
        assertEquals(focus, task.focus)
        assertEquals(post, task.localFirePost)
        assertFalse(TacticalFirePosts.withinPost(post, post.add(16.0, 0.0, 0.0)))
        val mover = TacticalTask(TacticalJob.FLANK, focus, focus, 1)
        assertFalse(TacticalFirePosts.recover(mover, post))
        assertFalse(mover.localFireRecovery)
    }

    @Test fun `a shooter can search past a blocked four block neighborhood while staying at its post`() {
        val post = Vec3(0.0, 64.0, 0.0)
        val candidates = TacticalFirePosts.probes(post)
        assertTrue(candidates.any { it.x > 7.0 && kotlin.math.abs(it.z) < 0.01 })
        assertTrue(candidates.all { TacticalFirePosts.withinPost(post, it) })
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test fun `waiting shooters keep a moving wave covered when the designated support loses its lane`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.advance(squad, state, plan, view())
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        val waiter = plan.tasks.entries.first { it.value.job == TacticalJob.WAIT }.key
        val onlyWaitingFire = view(100).copy(
            members = members.map { it.copy(canFire = it.id == waiter, recentFire = it.id == waiter) },
        )
        TacticalCoordinator.advance(squad, state, plan, onlyWaitingFire)
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        assertEquals(100L, plan.lastCover)
        assertEquals(1, TacticalEvidence.transition(plan, onlyWaitingFire).coverFired)
    }

    @Test fun `arrived flankers cover immediately and a late straggler cannot cancel their progress`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.advance(squad, state, plan, view())
        val wave = plan.tasks.filterValues { it.job == TacticalJob.FLANK }.keys.toList()
        assertEquals(6, wave.size)
        val arrived = wave.take(5).toSet()
        val positions = arrived.associateWith { plan.tasks[it]!!.anchor }
        fun arrivedView(now: Long) =
            view(now).copy(
                members = members.map { member ->
                    positions[member.id]?.let { point ->
                        plan.tasks[member.id]?.position = point
                        member.copy(position = point)
                    } ?: member
                },
            )
        TacticalCoordinator.advance(squad, state, plan, arrivedView(40))
        arrived.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
        assertEquals(arrived, (plan.behavior as FlankingState).arrived)
        TacticalCoordinator.advance(squad, state, plan, arrivedView(410))
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        assertEquals(TacticalFailure.NONE, plan.failure)
        assertTrue(wave.last() in plan.failedMembers)
        assertTrue(events.filterIsInstance<TacticalEvent.TaskReleased>().any { it.member == wave.last() && it.trigger == "flank_straggler_timeout" })
        TacticalCoordinator.advance(squad, state, plan, arrivedView(420))
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
        arrived.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
    }

    @Test fun `holding a defensive sector keeps tasks beyond phase and plan lifetime limits`() {
        val state = SquadTacticalState()
        val initial = view().copy(order = SquadOrder.DEFEND, home = Vec3(0.0, 64.0, 0.0))
        state.assess(squad, initial)
        val plan = state.plan!!
        val tasks = plan.tasks.toMap()
        assertTrue(tasks.isNotEmpty())
        state.assess(squad, initial.copy(now = 1500, contacts = initial.contacts.map { it.copy(seenAt = 1500) }))
        assertSame(plan, state.plan)
        tasks.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        state.assess(squad, initial.copy(now = 1501, stamp = 2))
        assertNotSame(plan, state.plan)
    }

    @Test fun `encirclement stays at rifle distance and flank paths cannot cut into close combat`() {
        val plan = TacticalPlan(1, TacticalPattern.ENCIRCLE, focus, 0, 1)
        TacticalCoordinator.assign(squad, plan, view())
        plan.tasks.values.filter { it.job == TacticalJob.WAIT }.forEach {
            assertTrue(it.anchor.subtract(focus).horizontalDistance() >= 48.0 - 0.01)
            assertFalse(EncirclePositions.candidate(view(), TacticalTask(TacticalJob.FLANK, it.anchor, focus, 1), focus.add(8.0, 0.0, 0.0), it.anchor))
        }
        val from = focus.add(-80.0, 0.0, 0.0)
        assertFalse(TacticalRoutes.safeFlankDistance(from, focus.add(-10.0, 0.0, 0.0), focus))
        assertTrue(TacticalRoutes.safeFlankDistance(focus.add(-8.0, 0.0, 0.0), focus.add(-12.0, 0.0, 0.0), focus))
    }

    @Test fun `reinforcements join lasting defence and a recovered fighter leaves reserve without resetting other posts`() {
        val state = SquadTacticalState()
        val origin = Vec3(0.0, 64.0, 0.0)
        val fighter = members[0]
        val patient = members[1].copy(ready = false, health = 0.2)
        val initial = view().copy(order = SquadOrder.DEFEND, home = origin, members = listOf(fighter, patient), peakStrength = 2)
        state.assess(squad, initial)
        val plan = state.plan!!
        val retained = plan.tasks[fighter.id]!!
        assertEquals(TacticalJob.RESERVE, plan.tasks[patient.id]?.job)
        val reinforcement = TacticalMember(UUID(5, 9), origin.add(8.0, 0.0, 0.0))
        val next = initial.copy(now = 10, members = listOf(fighter, patient.copy(ready = true, health = 1.0), reinforcement))
        state.assess(squad, next)
        assertSame(plan, state.plan)
        assertSame(retained, plan.tasks[fighter.id])
        assertEquals(TacticalJob.COVER, plan.tasks[patient.id]?.job)
        assertEquals(TacticalJob.COVER, plan.tasks[reinforcement.id]?.job)
    }
}

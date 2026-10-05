package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalLifecycleTest {
    private val squad = UUID(0, 7)
    private val origin = Vec3(0.0, 64.0, 0.0)
    private val focus = Vec3(50.0, 64.0, 0.0)
    private val members = (0..5).map { TacticalMember(UUID(0, it.toLong()), Vec3(it * 5.0, 64.0, 0.0), canFire = true) }
    private fun view(now: Long = 0, stamp: Int = 1) = TacticalSnapshot(now, SquadOrder.ATTACK, stamp, origin, focus, members,
        listOf(TacticalContact(UUID(1, 1), focus, now)))

    private class RecordingState(private val calls: MutableList<String>) : TacticalState() {
        override fun onEnter(context: TacticalContext) { calls += "enter:${context.plan.id}" }
        override fun beforeTick(context: TacticalContext) { calls += "tick:${context.plan.id}" }
        override fun onExit(context: TacticalContext, trigger: String) { calls += "exit:${context.plan.id}" }
        override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.cover(member)
    }

    @Test fun `repeated assessments tick the same state without reentering or replacing its tasks`() {
        val calls = mutableListOf<String>()
        val events = mutableListOf<TacticalEvent>()
        val registry = TacticalStates.registry.replacing(TacticalPattern.FOLLOW_ORDER) { RecordingState(calls) }
        val state = SquadTacticalState(TacticalEventSink(events::add), registry, TacticalDecisionPolicy {
            TacticalChoice(TacticalPattern.FOLLOW_ORDER, null)
        })
        state.assess(squad, view())
        val plan = state.plan!!
        val tasks = plan.tasks.toMap()
        state.assess(squad, view(10))
        state.assess(squad, view(20))
        assertEquals(1, calls.count { it.startsWith("enter:") })
        assertEquals(3, calls.count { it.startsWith("tick:") })
        assertSame(plan, state.plan)
        tasks.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        assertEquals(members.size, events.filterIsInstance<TacticalEvent.TaskAssigned>().size)
    }

    @Test fun `a new player order exits and releases the old state before entering the new one`() {
        val calls = mutableListOf<String>()
        val events = mutableListOf<TacticalEvent>()
        val registry = TacticalStates.registry.replacing(TacticalPattern.FOLLOW_ORDER) { RecordingState(calls) }
        val state = SquadTacticalState(TacticalEventSink(events::add), registry, TacticalDecisionPolicy {
            TacticalChoice(TacticalPattern.FOLLOW_ORDER, null)
        })
        state.assess(squad, view())
        val old = state.plan!!
        events.clear()
        state.assess(squad, view(10, 2))
        assertTrue(calls.indexOf("exit:1") < calls.indexOf("enter:2"))
        assertTrue(old.tasks.isEmpty())
        val released = events.filterIsInstance<TacticalEvent.TaskReleased>()
        assertEquals(members.map { it.id }.toSet(), released.map { it.member }.toSet())
        assertTrue(released.all { it.trigger == "player_order_changed" })
        val firstNewEnter = events.indexOfFirst { it is TacticalEvent.Lifecycle && it.action == "state_enter" && it.plan == 2 }
        assertTrue(events.withIndex().filter { it.value is TacticalEvent.TaskReleased }.all { it.index < firstNewEnter })
        val count = calls.size
        TacticalCoordinator.advance(squad, state, old, view(20))
        assertEquals(count, calls.size, "An exited plan cannot run again")
    }

    @Test fun `each pattern creates an isolated state and every pattern is registered`() {
        for (pattern in TacticalPattern.entries) {
            val a = TacticalPlan(1, pattern, focus, 0, 1)
            val b = TacticalPlan(2, pattern, focus, 0, 1)
            assertNotSame(a.behavior, b.behavior)
            assertNotSame(a.behavior.phases, b.behavior.phases)
            assertNotSame(a.assignments, b.assignments)
            TacticalCoordinator.assign(squad, a, view())
            assertTrue(b.tasks.isEmpty())
            assertEquals(TacticalStatus.PREPARING, b.status)
        }
        assertThrows(IllegalArgumentException::class.java) { TacticalStateRegistry(emptyMap()) }
    }

    @Test fun `deactivation releases tasks once and a returning squad gets a fresh plan with its contacts preserved`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        val contact = view().contacts.single()
        state.contacts[contact.id] = contact
        state.assess(squad, view())
        val old = state.plan!!
        val assigned = old.tasks.keys.toSet()
        events.clear()
        state.deactivate(50, "no_ground_members")
        state.deactivate(60, "no_ground_members")
        assertNull(state.plan)
        assertTrue(old.tasks.isEmpty())
        assertEquals(contact, state.contacts[contact.id])
        assertEquals(assigned, events.filterIsInstance<TacticalEvent.TaskReleased>().map { it.member }.toSet())
        assertEquals(assigned.size, events.filterIsInstance<TacticalEvent.TaskReleased>().size)
        assertEquals(1, events.filterIsInstance<TacticalEvent.Lifecycle>().count { it.action == "state_exit" })
        state.assess(squad, view(70))
        assertNotSame(old, state.plan)
        assertNotSame(old.behavior, state.plan!!.behavior)
        assertTrue(state.plan!!.id > old.id)
        assertFalse(state.plan!!.tasks.isEmpty())
    }

    @Test fun `selection observers cannot replace or deactivate a plan halfway through replacement`() {
        val events = mutableListOf<TacticalEvent>()
        lateinit var state: SquadTacticalState
        var rejected = 0
        state = SquadTacticalState(TacticalEventSink {
            events += it
            if (it is TacticalEvent.TaskReleased) {
                assertThrows(IllegalStateException::class.java) {
                    state.select(TacticalChoice(TacticalPattern.EVADE, focus), 9, it.tick)
                }
                assertThrows(IllegalStateException::class.java) { state.deactivate(it.tick, "reentrant") }
                rejected++
            }
        })
        state.select(TacticalChoice(TacticalPattern.REORIENT, focus), 1, 0)
        val old = state.plan!!
        TacticalCoordinator.assign(squad, old, view())
        state.select(TacticalChoice(TacticalPattern.BOUND, focus), 2, 10)
        assertEquals(members.size, rejected)
        assertEquals(TacticalPattern.BOUND, state.plan!!.pattern)
        assertEquals(2, events.filterIsInstance<TacticalEvent.PlanSelected>().size)
        assertTrue(old.tasks.isEmpty())
        assertTrue(state.select(TacticalChoice(TacticalPattern.REORIENT, focus), 3, 20))
    }

    @Test fun `a shared mutable state cannot be attached to two plans`() {
        val behavior = FlankState()
        TacticalPlan(1, TacticalPattern.FLANK, focus, 0, 1, behavior = behavior)
        assertThrows(IllegalStateException::class.java) { TacticalPlan(2, TacticalPattern.FLANK, focus, 0, 1, behavior = behavior) }
    }

    @Test fun `an invalid state factory leaves the running plan and its resources intact`() {
        val calls = mutableListOf<String>()
        val shared = RecordingState(calls)
        val registry = TacticalStates.registry.replacing(TacticalPattern.FOLLOW_ORDER) { shared }
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add), registry, TacticalDecisionPolicy {
            TacticalChoice(TacticalPattern.FOLLOW_ORDER, null)
        })
        state.assess(squad, view())
        val old = state.plan!!
        val tasks = old.tasks.toMap()
        events.clear()
        assertThrows(IllegalStateException::class.java) { state.assess(squad, view(10, 2)) }
        assertSame(old, state.plan)
        tasks.forEach { (id, task) -> assertSame(task, old.tasks[id]) }
        assertTrue(events.none { it is TacticalEvent.TaskReleased })
        state.assess(squad, view(20))
        assertEquals(2, calls.count { it.startsWith("tick:") })
        assertEquals(0, calls.count { it.startsWith("exit:") })
    }

    @Test fun `a failure during state entry sets recovery policy without assigning or ticking terminal work`() {
        val events = mutableListOf<TacticalEvent>()
        var ticks = 0
        val registry = TacticalStates.registry.replacing(TacticalPattern.BOUND) {
            object : TacticalState() {
                override fun onEnter(context: TacticalContext) = context.fail(TacticalFailure.MOVEMENT_TIMEOUT)
                override fun beforeTick(context: TacticalContext) { ticks++ }
                override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.cover(member)
            }
        }
        val state = SquadTacticalState(TacticalEventSink(events::add), registry, TacticalDecisionPolicy {
            TacticalChoice(TacticalPattern.BOUND, focus)
        })
        state.assess(squad, view())
        assertEquals(TacticalPhase.FAILED, state.plan!!.phase)
        assertEquals(TacticalFailure.MOVEMENT_TIMEOUT, state.plan!!.failure)
        assertEquals(120L, state.blocked[TacticalPattern.BOUND])
        assertEquals(0, ticks)
        assertTrue(events.none { it is TacticalEvent.TaskAssigned })
        state.assess(squad, view(10))
        assertNotEquals(TacticalPattern.BOUND, state.plan!!.pattern)
    }

    @Test fun `a registered state can enable defensive support posts without changing the layout dispatcher`() {
        val registry = TacticalStates.registry.replacing(TacticalPattern.FOLLOW_ORDER) {
            object : TacticalState() {
                override val supportsDefensiveOverwatch = true
                override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.cover(member)
            }
        }
        val state = SquadTacticalState(registry = registry, decisions = TacticalDecisionPolicy {
            TacticalChoice(TacticalPattern.FOLLOW_ORDER, focus)
        })
        val defence = view().copy(order = SquadOrder.DEFEND, home = origin,
            members = members.mapIndexed { index, member ->
                if (index == 0) member.copy(role = NpcClass.SNIPER) else member
            })
        state.assess(squad, defence)
        val old = state.plan!!.tasks[members.first().id]!!
        assertEquals(TacticalJob.OVERWATCH, old.job)
        old.position = origin.add(4.0, 5.0, 0.0)
        state.assess(squad, defence.copy(now = 250))
        val next = state.plan!!.tasks[members.first().id]!!
        assertNotSame(old, next)
        assertEquals(TacticalJob.OVERWATCH, next.job)
        assertEquals(old.anchor, next.anchor)
        assertEquals(old.position, next.position)
    }

    @Test fun `a phase machine cannot tick or transition another squads context`() {
        val a = TacticalPlan(1, TacticalPattern.FLANK, focus, 0, 1)
        val b = TacticalPlan(2, TacticalPattern.FLANK, focus, 0, 1)
        TacticalCoordinator.assign(squad, a, view())
        TacticalCoordinator.assign(squad, b, view())
        val other = TacticalContext(squad, b, view())
        assertThrows(IllegalStateException::class.java) { a.behavior.phases.tick(other) }
        assertThrows(IllegalStateException::class.java) { a.behavior.phases.transition(ExecutingPhase(), other, "wrong_owner") }
        assertEquals(TacticalPhase.PREPARING, a.phase)
        assertEquals(TacticalPhase.PREPARING, b.phase)
    }

    @Test fun `plan failure releases all tasks once and applies recovery holds before cleanup`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, view())
        val assigned = plan.tasks.keys.toSet()
        events.clear()
        state.fail(20, TacticalFailure.MOVEMENT_TIMEOUT)
        assertEquals(TacticalPhase.FAILED, plan.phase)
        assertEquals(TacticalFailure.MOVEMENT_TIMEOUT, plan.failure)
        assertTrue(plan.tasks.isEmpty())
        assertTrue(assigned.all { state.holdsAfterFailure(it, 1, 21) })
        assertEquals(assigned, events.filterIsInstance<TacticalEvent.TaskReleased>().map { it.member }.toSet())
        val count = events.size
        state.fail(30, TacticalFailure.COVER_LOST)
        TacticalCoordinator.assign(squad, plan, view(30))
        TacticalCoordinator.advance(squad, state, plan, view(30))
        assertEquals(count, events.size)
        assertEquals(TacticalFailure.MOVEMENT_TIMEOUT, plan.failure)
    }

    @Test fun `terminal completion cannot reassign tasks or restart the phase clock`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.REORIENT, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, view())
        TacticalCoordinator.advance(squad, state, plan, view())
        plan.complete(20, "objective_reached")
        val tasks = plan.tasks.toMap()
        val count = events.size
        plan.complete(30, "duplicate_completion")
        TacticalCoordinator.assign(squad, plan, view(30))
        TacticalCoordinator.advance(squad, state, plan, view(30))
        assertEquals(TacticalPhase.COMPLETED, plan.phase)
        assertEquals(20L, plan.phaseSince)
        assertEquals(count, events.size)
        tasks.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        assertThrows(IllegalStateException::class.java) {
            plan.behavior.phases.transition(PreparingPhase(), TacticalContext(squad, plan, view(40)), "restart_terminal")
        }
    }

    @Test fun `a short firing lane probe produces explicit transitions with readiness evidence`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        val initial = view().copy(members = members.map { it.copy(canFire = false, recentFire = false) })
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, initial)
        TacticalCoordinator.advance(squad, state, plan, initial.copy(now = 80, contacts = initial.contacts.map { it.copy(seenAt = 80) }))
        assertEquals(TacticalPhase.OPENING_LANE, plan.phase)
        val transition = events.filterIsInstance<TacticalEvent.Transition>().last()
        assertEquals(TacticalPhase.PREPARING, transition.from)
        assertEquals(TacticalPhase.OPENING_LANE, transition.to)
        assertEquals("cover_lane_blocked", transition.trigger)
        assertEquals(0, transition.evidence.coverFired)
        assertEquals(6, transition.evidence.members)
        assertEquals(2, plan.tasks.values.count { it.opensLane })
        assertTrue(events.any { it is TacticalEvent.Lifecycle && it.action == "phase_exit" && it.phase == TacticalPhase.PREPARING })
        assertTrue(events.any { it is TacticalEvent.Lifecycle && it.action == "phase_enter" && it.phase == TacticalPhase.OPENING_LANE })
    }

    @Test fun `a retained cover task keeps its search progress and emits no cancellation`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, view())
        val cover = plan.tasks.filterValues { it.job == TacticalJob.COVER }.toMap()
        cover.values.forEach { it.position = it.anchor; it.failures = 2; it.nextSearch = 91 }
        events.clear()
        TacticalCoordinator.advance(squad, state, plan, view(10))
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        cover.forEach { (id, task) ->
            assertSame(task, plan.tasks[id])
            assertEquals(2, task.failures)
            assertEquals(91L, task.nextSearch)
        }
        assertFalse(events.filterIsInstance<TacticalEvent.TaskReleased>().any { it.member in cover })
    }

    @Test fun `rejected replanning is logged at a bounded frequency with a concrete guard reason`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.FLANK, focus), 1, 0)
        for (tick in 1L..200L) assertFalse(state.select(TacticalChoice(TacticalPattern.SEARCH, focus), 1, tick))
        val held = events.filterIsInstance<TacticalEvent.SelectionHeld>()
        assertEquals(2, held.size)
        assertTrue(held.all { it.reason == TacticalSelectionHold.COMMITTED_MANEUVER })
        assertEquals(listOf(1L, 101L), held.map { it.tick })
    }

    @Test fun `invalid replacement assignments leave the old task intact`() {
        val id = members.first().id
        val board = TacticalTaskBoard(1, TacticalEventSink.NONE)
        val task = TacticalTask(TacticalJob.COVER, origin, focus, 1)
        board.put(id, task, 0, "initial")
        assertThrows(IllegalArgumentException::class.java) {
            board.replace(mapOf(id to TacticalTask(TacticalJob.FLANK, focus, focus, 2)), 10, "wrong_plan")
        }
        assertSame(task, board.tasks[id])
        assertThrows(UnsupportedOperationException::class.java) { (board.tasks as MutableMap<UUID, TacticalTask>).clear() }
    }

    @Test fun `a failed member stays outside subsequent assignments and pruning releases only absent members`() {
        val events = mutableListOf<TacticalEvent>()
        val board = TacticalTaskBoard(1, TacticalEventSink(events::add))
        val desired = members.associate { it.id to TacticalTask(TacticalJob.COVER, it.position, focus, 1) }
        board.replace(desired, 0, "initial")
        board.abandon(members[0].id, 10, "unreachable")
        board.replace(desired, 20, "next_wave", retainCover = true)
        assertNull(board.tasks[members[0].id])
        events.clear()
        board.prune(members.drop(2).map { it.id }.toSet(), 30)
        assertEquals(listOf(members[1].id), events.filterIsInstance<TacticalEvent.TaskReleased>().map { it.member })
        assertEquals(4, board.tasks.size)
    }

    @Test fun `trace buffer overflow never drops synchronous task cleanup`() {
        val buffer = TacticalEventBuffer(2)
        val stream = TacticalEventStream(buffer)
        val released = mutableListOf<UUID>()
        stream.runtime = TacticalEventSink { if (it is TacticalEvent.TaskReleased) released += it.member }
        val board = TacticalTaskBoard(1, stream)
        for (member in members) board.put(member.id, TacticalTask(TacticalJob.COVER, origin, focus, 1), 0, "initial")
        buffer.drain()
        board.clear(10, "cancel")
        assertEquals(members.map { it.id }, released)
        val batch = buffer.drain()
        assertEquals(2, batch.events.size)
        assertEquals(4, batch.dropped)
    }

    @Test fun `releasing a tactical path leaves a newer cover path untouched even with identical nodes`() {
        val task = TacticalTask(TacticalJob.FLANK, focus, focus, 1)
        fun path() = Path(listOf(Node(10, 64, 0)), BlockPos(10, 64, 0), true)
        val tactical = path()
        var stopped = 0
        task.navigationPath = tactical
        task.releaseNavigation(path()) { stopped++ }
        assertEquals(0, stopped)
        assertNull(task.navigationPath)
        task.navigationPath = tactical
        task.releaseNavigation(tactical) { stopped++ }
        task.releaseNavigation(tactical) { stopped++ }
        assertEquals(1, stopped)
    }

    @Test fun `paused movement preserves active progress time and repeated pause observations do not restart it`() {
        val task = TacticalTask(TacticalJob.FLANK, focus, focus, 1).also { it.lastProgress = 20 }
        assertTrue(task.suspend(40, "cover_lost"))
        assertFalse(task.suspend(80, "cover_lost"))
        assertEquals(40L, task.pausedAt)
        assertTrue(task.resume(100))
        assertEquals(80L, task.lastProgress)
        assertEquals(100L, task.nextSearch)
        assertFalse(task.resume(110))
        assertEquals(80L, task.lastProgress)
    }
}

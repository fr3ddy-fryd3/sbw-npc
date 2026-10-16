package com.sbwnpc.squad.ai.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PlanRuntimeTest {
    private val agent = AgentId(AgentLevel.NPC, UUID(0, 1))
    private val owner = PlanOwner(agent, UUID(1, 1))
    private fun facts(now: Long) = FactSnapshot(owner.agent, now)
    private val arrived = FactKey("arrived", Boolean::class.javaObjectType)
    private val cover = FactKey("cover", Boolean::class.javaObjectType)
    private val motion = ResourceKey(agent, "motion")
    private class Action(var response: ActionUpdate = ActionUpdate.Running, var reopenable: Boolean = false) : ActionExecution {
        var starts = 0
        var ticks = 0
        var pauses = 0
        var resumes = 0
        var cancellations = 0
        var reopenings = 0
        var disposals = 0
        override fun start(now: Long): ActionUpdate {
            starts++
            return response
        }
        override fun tick(now: Long): ActionUpdate {
            ticks++
            return response
        }
        override fun suspend(now: Long, reason: String) {
            pauses++
        }
        override fun resume(now: Long) {
            resumes++
        }
        override fun cancel(now: Long, reason: String) {
            cancellations++
        }
        override fun reopen(now: Long): Boolean {
            reopenings++
            return reopenable
        }
        override fun dispose(now: Long, reason: String) {
            disposals++
        }
    }
    private fun task(id: String, after: Set<String> = emptySet(), goal: Int = 1, contract: TaskContract = TaskContract(), resources: Set<ResourceKey> = emptySet()) = PlannedTask(id, PrimitiveSpec(id, ActionSpec(ActionKey("move", Int::class.javaObjectType), goal, resources), contract), after)
    private fun plan(vararg tasks: PlannedTask, owner: PlanOwner = this.owner) = HtnPlan(owner, tasks.toList(), emptyList(), emptyList())
    private fun complete(now: Long) = ActionUpdate.Completed(listOf(ActionEvidence("physical_arrival", now)))

    @Test fun `equivalent rebuilt plan retains live action cursor even when contract objects are recreated`() {
        val action = Action()
        var creates = 0
        val runtime = PlanRuntime({ _, _ ->
            creates++
            if (creates == 1) action else Action()
        }, ResourceLeases())
        fun move() = task("move", contract = TaskContract(maintain = Condition.test(cover, "lost_cover") { it }))
        runtime.install(plan(move()), facts(0).observe(cover, true))
        runtime.tick(facts(0).observe(cover, true))
        val ticket = runtime.reports.getValue("move").ticket
        runtime.install(plan(move(), task("new_reinforcement")), facts(1).observe(cover, true))
        runtime.tick(facts(1).observe(cover, true))
        assertEquals(ticket, runtime.reports.getValue("move").ticket)
        assertEquals(0, action.cancellations)
        assertEquals(1, action.starts)
        assertEquals(2, creates, "Only the new reinforcement must create another execution")
    }

    @Test fun `new order revokes leases and ignores completion from old generation`() {
        val actions = ArrayList<Action>()
        val leases = ResourceLeases()
        val runtime = PlanRuntime({ _, _ -> Action().also(actions::add) }, leases)
        runtime.install(plan(task("move", resources = setOf(motion))), facts(0))
        runtime.tick(facts(0))
        val stale = runtime.reports.getValue("move").ticket
        runtime.install(plan(task("move", resources = setOf(motion)), owner = owner.copy(order = UUID(2, 2))), facts(1))
        assertNull(leases.owner(motion))
        assertEquals(1, actions.first().cancellations)
        runtime.tick(facts(1))
        assertFalse(runtime.accept(stale, complete(1), facts(1)))
        assertEquals(ExecutionStatus.RUNNING, runtime.reports.getValue("move").status)
    }

    @Test fun `failure blocks dependent task while independent actions keep running`() {
        val actions = HashMap<Int, Action>()
        val runtime = PlanRuntime({ _, spec -> actions.getOrPut(spec.parameters as Int) { Action(if (spec.parameters == 1) ActionUpdate.Failed("route_unreachable") else ActionUpdate.Running) } }, ResourceLeases())
        runtime.install(plan(task("flank", goal = 1), task("dependent", setOf("flank"), goal = 2), task("independent", goal = 3)), facts(0))
        runtime.tick(facts(0))
        assertEquals(ExecutionStatus.FAILED, runtime.reports.getValue("flank").status)
        assertEquals("dependency_failed", runtime.reports.getValue("dependent").reason)
        assertEquals(ExecutionStatus.RUNNING, runtime.reports.getValue("independent").status)
        assertFalse(actions.containsKey(2))
        runtime.tick(facts(1))
        assertTrue(actions.getValue(3).ticks > 0)
        assertFalse(runtime.completed)
    }

    @Test fun `cover loss suspends only covered mover and excludes paused time from timeout`() {
        val mover = Action()
        val support = Action()
        val runtime = PlanRuntime({ _, spec -> if (spec.parameters == 1) mover else support }, ResourceLeases())
        val guarded = task("move", contract = TaskContract(maintain = Condition.test(cover, "lost_cover") { it }))
        runtime.install(plan(guarded, task("support", goal = 2)), facts(0).observe(cover, true))
        runtime.tick(facts(0).observe(cover, true))
        runtime.tick(facts(1).observe(cover, false))
        assertEquals(1, mover.pauses)
        assertEquals(0, support.pauses)
        assertFalse(runtime.accept(runtime.reports.getValue("move").ticket, complete(1), facts(1).observe(cover, false)))
        runtime.tick(facts(1000).observe(cover, true))
        assertEquals(1, mover.resumes)
        assertEquals(ExecutionStatus.RUNNING, runtime.reports.getValue("move").status)
        assertEquals(0, runtime.reports.getValue("move").activeTicks)
    }

    @Test fun `completion requires current observed goal not predicted path success`() {
        val action = Action(complete(0))
        val runtime = PlanRuntime({ _, _ -> action }, ResourceLeases())
        val move = task("move", contract = TaskContract(completed = Condition.test(arrived, "not_arrived") { it }))
        runtime.install(plan(move), facts(0).observe(arrived, false))
        runtime.tick(facts(0).observe(arrived, false))
        assertEquals(ExecutionStatus.SUSPENDED, runtime.reports.getValue("move").status)
        assertFalse(runtime.completed)
        action.response = complete(1)
        runtime.tick(facts(1).observe(arrived, true))
        assertTrue(runtime.completed)
        assertEquals(1, action.starts)
    }

    @Test fun `same tick repeated update does not repeat execution even after suspension`() {
        val action = Action()
        val runtime = PlanRuntime({ _, _ -> action }, ResourceLeases())
        runtime.install(plan(task("move", contract = TaskContract(maintain = Condition.test(cover, "lost_cover") { it }))), facts(0).observe(cover, true))
        runtime.tick(facts(0).observe(cover, true))
        runtime.tick(facts(0).observe(cover, false))
        runtime.tick(facts(0).observe(cover, true))
        assertEquals(1, action.starts)
        assertEquals(0, action.ticks)
    }

    @Test fun `resource acquisition is atomic and cancellation lets waiting actor proceed`() {
        val leases = ResourceLeases()
        val other = ResourceKey(agent, "weapon")
        val a = ExecutionTicket(owner, "a", 1, UUID(0, 9))
        val b = ExecutionTicket(owner, "b", 2, UUID(0, 9))
        assertTrue(leases.acquire(a, setOf(other), 0, 0))
        assertFalse(leases.acquire(b, setOf(motion, other), 0, 0))
        assertNull(leases.owner(motion), "Waiting for one resource must not reserve another")
        leases.release(a)
        assertTrue(leases.acquire(b, setOf(motion, other), 0, 1))
        leases.release(b)
        assertNull(leases.owner(motion))
        assertEquals(0, leases.waitingCount)
    }

    @Test fun `stale proposed plan cannot cancel current running work`() {
        val action = Action()
        val runtime = PlanRuntime({ _, _ -> action }, ResourceLeases())
        runtime.install(plan(task("move")), facts(0))
        runtime.tick(facts(0))
        val proposed = plan(task("other")).copy(dependencies = listOf(FactDependency(cover, false, true)))
        assertFalse(runtime.install(proposed, facts(1).observe(cover, false)))
        assertEquals(0, action.cancellations)
        assertTrue("move" in runtime.reports)
    }

    @Test fun `execution graph rejects cycles before any action can start`() {
        assertThrows(IllegalArgumentException::class.java) {
            plan(task("a", setOf("b")), task("b", setOf("a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(task("a", setOf("missing")))
        }
    }

    @Test fun `budget deferral does not consume action timeout and retains one execution`() {
        val action = Action(ActionUpdate.Deferred("path_budget"))
        val runtime = PlanRuntime({ _, _ -> action }, ResourceLeases())
        runtime.install(plan(task("move")), facts(0))
        runtime.tick(facts(0))
        runtime.tick(facts(1000))
        assertEquals(0, runtime.reports.getValue("move").activeTicks)
        action.response = ActionUpdate.Running
        runtime.tick(facts(1001))
        runtime.tick(facts(1002))
        assertEquals(1, runtime.reports.getValue("move").activeTicks)
        assertEquals(1, action.starts)
    }

    @Test fun `bounded runtime visits every independent actor without starvation`() {
        val actions = ArrayList<Action>()
        val runtime = PlanRuntime({ _, _ -> Action().also(actions::add) }, ResourceLeases())
        runtime.install(plan(*(0 until 600).map { task("npc$it") }.toTypedArray()), facts(0))
        repeat(10) { runtime.tick(facts(it.toLong()), work = 64) }
        assertEquals(600, actions.size)
        assertTrue(runtime.reports.values.all { it.status == ExecutionStatus.RUNNING })
    }

    @Test fun `foreign observations cannot drive runtime`() {
        val runtime = PlanRuntime({ _, _ -> Action() }, ResourceLeases())
        val foreign = FactSnapshot(AgentId(AgentLevel.NPC, UUID(5, 5)), 0)
        assertThrows(IllegalArgumentException::class.java) { runtime.install(plan(task("move")), foreign) }
        runtime.install(plan(task("move")), facts(0))
        assertThrows(IllegalArgumentException::class.java) { runtime.tick(foreign) }
        assertEquals(ExecutionStatus.PENDING, runtime.reports.getValue("move").status)
    }

    @Test fun `synchronous intent revision retains cursor but rejects an old external completion`() {
        var currentGoal = 1
        var instances = 0
        val runtime = PlanRuntime({ _, _ ->
            instances++
            object : ActionExecution {
                override fun start(now: Long) = ActionUpdate.Running
                override fun tick(now: Long) = if (currentGoal == 3) complete(now) else ActionUpdate.Running
                override fun suspend(now: Long, reason: String) = Unit
                override fun resume(now: Long) = Unit
                override fun cancel(now: Long, reason: String) = Unit
                override fun revise(spec: ActionSpec<*>, now: Long): Boolean {
                    currentGoal = spec.parameters as Int
                    return true
                }
            }
        }, ResourceLeases())
        runtime.install(plan(task("move", goal = 1)), facts(0))
        runtime.tick(facts(0))
        val oldTicket = runtime.reports.getValue("move").ticket
        runtime.install(plan(task("move", goal = 2)), facts(1))
        assertEquals(oldTicket, runtime.reports.getValue("move").ticket)
        assertFalse(runtime.accept(oldTicket, complete(1), facts(1)))
        assertFalse(runtime.completed)
        runtime.install(plan(task("move", goal = 3)), facts(2))
        runtime.tick(facts(2))
        assertTrue(runtime.completed)
        assertEquals(1, instances)
    }

    @Test fun `removing completed post disposes its claim without cancelling a finished action`() {
        var disposed = 0
        var cancelled = 0
        val runtime = PlanRuntime({ _, _ ->
            object : ActionExecution {
                override fun start(now: Long) = complete(now)
                override fun tick(now: Long) = complete(now)
                override fun suspend(now: Long, reason: String) = Unit
                override fun resume(now: Long) = Unit
                override fun cancel(now: Long, reason: String) {
                    cancelled++
                }
                override fun dispose(now: Long, reason: String) {
                    disposed++
                }
            }
        }, ResourceLeases())
        runtime.install(plan(task("post")), facts(0))
        runtime.tick(facts(0))
        assertTrue(runtime.completed)
        runtime.clear(1, "new_order")
        runtime.clear(2, "already_removed")
        assertEquals(1, disposed)
        assertEquals(0, cancelled)
    }

    @Test fun `lost completion reopens an opted in action without disposing its retained state`() {
        val action = Action(reopenable = true)
        val leases = ResourceLeases()
        var creates = 0
        val runtime = PlanRuntime({ _, _ ->
            creates++
            action
        }, leases)
        val post = task("post", contract = TaskContract(completed = Condition.test(arrived, "not_arrived") { it }), resources = setOf(motion))
        runtime.install(plan(post), facts(0).observe(arrived, false))
        runtime.tick(facts(0).observe(arrived, false))
        action.response = complete(1)
        runtime.tick(facts(1).observe(arrived, true))
        val ticket = runtime.reports.getValue("post").ticket
        assertTrue(runtime.completed)
        assertNull(leases.owner(motion))

        runtime.install(plan(post), facts(10000).observe(arrived, false))
        val reopened = runtime.reports.getValue("post")
        assertEquals(ticket, reopened.ticket)
        assertEquals(ExecutionStatus.RUNNING, reopened.status)
        assertTrue(reopened.evidence.isEmpty())
        assertEquals(0L, reopened.activeTicks)
        assertFalse(runtime.accept(ticket, complete(10000), facts(10000).observe(arrived, true)))
        action.response = ActionUpdate.Running
        runtime.tick(facts(10000).observe(arrived, false))
        assertEquals(ticket, leases.owner(motion))
        assertEquals(1, creates)
        assertEquals(1, action.starts)
        assertEquals(1, action.reopenings)
        assertEquals(0, action.disposals)
        assertEquals(0, action.cancellations)

        action.response = complete(10001)
        runtime.tick(facts(10001).observe(arrived, true))
        assertTrue(runtime.completed)
        runtime.clear(10002, "new_order")
        assertEquals(1, action.disposals)
    }

    @Test fun `lost completion replaces an action that declines reopening`() {
        val actions = ArrayList<Action>()
        val runtime = PlanRuntime({ _, _ -> Action(complete(0)).also(actions::add) }, ResourceLeases())
        val post = task("post", contract = TaskContract(completed = Condition.test(arrived, "not_arrived") { it }))
        runtime.install(plan(post), facts(0).observe(arrived, true))
        runtime.tick(facts(0).observe(arrived, true))
        val ticket = runtime.reports.getValue("post").ticket
        runtime.install(plan(post), facts(1).observe(arrived, false))
        assertNotEquals(ticket, runtime.reports.getValue("post").ticket)
        assertEquals(1, actions.single().disposals)
        assertEquals(0, actions.single().cancellations)
    }

    @Test fun `a new order never reopens the old completed action`() {
        val action = Action(complete(0), reopenable = true)
        val runtime = PlanRuntime({ _, _ -> action }, ResourceLeases())
        val post = task("post", contract = TaskContract(completed = Condition.test(arrived, "not_arrived") { it }))
        runtime.install(plan(post), facts(0).observe(arrived, true))
        runtime.tick(facts(0).observe(arrived, true))
        val ticket = runtime.reports.getValue("post").ticket
        runtime.install(plan(post, owner = owner.copy(order = UUID(2, 2))), facts(1).observe(arrived, false))
        assertNotEquals(ticket, runtime.reports.getValue("post").ticket)
        assertEquals(0, action.reopenings)
        assertEquals(1, action.disposals)
    }
}

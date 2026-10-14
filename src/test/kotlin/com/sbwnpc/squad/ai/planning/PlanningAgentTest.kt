package com.sbwnpc.squad.ai.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PlanningAgentTest {
    private val id = AgentId(AgentLevel.SQUAD, UUID(0, 1))
    private val root = TaskKey("root", Unit::class.java)
    private val ready = FactKey("ready", Boolean::class.javaObjectType)
    private val irrelevant = FactKey("irrelevant", Boolean::class.javaObjectType)
    private val action = ActionKey("work", String::class.java)
    private class Work : ActionExecution {
        var starts = 0
        var cancelled = 0
        var paused = 0
        var ticks = 0
        override fun start(now: Long): ActionUpdate {
            starts++
            return ActionUpdate.Running
        }
        override fun tick(now: Long): ActionUpdate {
            ticks++
            return ActionUpdate.Running
        }
        override fun suspend(now: Long, reason: String) {
            paused++
        }
        override fun resume(now: Long) = Unit
        override fun cancel(now: Long, reason: String) {
            cancelled++
        }
    }
    private fun facts(now: Long) = FactSnapshot(id, now)
    private fun domain(condition: Condition = Condition.ALWAYS) =
        HtnDomain(
            AgentLevel.SQUAD,
            listOf(
                CompoundTask(root, listOf(Method("method", eligible = { condition }, decompose = { _, _ -> TaskNetwork.sequence(TaskKey("leaf", Unit::class.java).call(Unit)) }))),
                PrimitiveTask(TaskKey("leaf", Unit::class.java)) { PrimitiveSpec("stable", ActionSpec(action, "same"), TaskContract()) },
            ),
        )

    @Test fun `unknown facts wake planning only when the missing input becomes available`() {
        val feedback = ArrayList<PlanningFeedback>()
        val work = Work()
        val agent = PlanningAgent(id, domain(Condition.test(ready, "not_ready") { it }), PlanRuntime({ _, _ -> work }, ResourceLeases()), feedback::add)
        agent.command(UUID(0, 9), root.call(Unit), facts(0))
        agent.tick(facts(0), planningWork = 10)
        assertEquals(PlanningStatus.WAITING_FOR_FACTS, agent.status)
        agent.tick(facts(1).observe(irrelevant, true), planningWork = 10)
        assertEquals(1, feedback.size)
        agent.tick(facts(2).observe(ready, true), planningWork = 10)
        assertEquals(PlanningStatus.EXECUTING, agent.status)
        assertEquals(1, work.starts)
    }

    @Test fun `failed search does not repeat every tick but relevant change permits a new attempt`() {
        val feedback = ArrayList<PlanningFeedback>()
        val agent = PlanningAgent(id, domain(Condition.test(ready, "not_ready") { it }), PlanRuntime({ _, _ -> Work() }, ResourceLeases()), feedback::add)
        agent.command(UUID(0, 9), root.call(Unit), facts(0).observe(ready, false))
        agent.tick(facts(0).observe(ready, false), planningWork = 10)
        repeat(20) { agent.tick(facts(it + 1L).observe(ready, false).observe(irrelevant, it % 2 == 0), planningWork = 10) }
        assertEquals(1, feedback.size)
        assertEquals(PlanningStatus.BLOCKED, agent.status)
        agent.tick(facts(21).observe(ready, true), planningWork = 10)
        assertEquals(PlanningStatus.EXECUTING, agent.status)
        assertEquals(2, feedback.size)
    }

    @Test fun `repair preserves live action while bounded planning continues`() {
        val work = Work()
        val agent = PlanningAgent(id, domain(), PlanRuntime({ _, _ -> work }, ResourceLeases()))
        agent.command(UUID(0, 9), root.call(Unit), facts(0))
        agent.tick(facts(0), planningWork = 10)
        agent.repair(facts(1))
        agent.tick(facts(1), planningWork = 1)
        assertEquals(PlanningStatus.PLANNING, agent.status)
        assertEquals(1, work.ticks)
        agent.tick(facts(2), planningWork = 10)
        assertEquals(PlanningStatus.EXECUTING, agent.status)
        assertEquals(1, work.starts)
        assertEquals(0, work.cancelled)
    }

    @Test fun `parent hold releases resources and ignores delayed completion until resumed`() {
        val work = Work()
        val resource = ResourceKey(id, "motion")
        val leases = ResourceLeases()
        val runtime = PlanRuntime({ _, _ -> work }, leases)
        val domain = HtnDomain(AgentLevel.SQUAD, listOf(PrimitiveTask(root) { PrimitiveSpec("work", ActionSpec(action, "same", setOf(resource)), TaskContract()) }))
        val agent = PlanningAgent(id, domain, runtime)
        agent.command(UUID(0, 9), root.call(Unit), facts(0))
        agent.tick(facts(0))
        val ticket = runtime.reports.getValue("work").ticket
        agent.hold(1, "parent_waiting")
        assertNull(leases.owner(resource))
        assertFalse(runtime.accept(ticket, ActionUpdate.Completed(listOf(ActionEvidence("arrived", 1))), facts(1)))
        agent.tick(facts(1000))
        assertEquals(0, work.ticks)
        agent.resume()
        agent.tick(facts(1001))
        assertEquals(1, work.starts)
        assertEquals(1, work.ticks)
        assertEquals(0, runtime.reports.getValue("work").activeTicks)
        assertTrue(runtime.isLive(ticket))
        agent.cancel(1002, "new_order")
        assertFalse(runtime.isLive(ticket))
        assertEquals(1, work.cancelled)
    }
}

package com.sbwnpc.squad.ai.coordination

import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Simulated execution verifies protocol wiring; this is not a Minecraft movement benchmark. */
class HierarchyExecutionTest {
    private data class Goal(val position: Int)
    private val task = TaskKey("reach", Goal::class.java)
    private val move = ActionKey("move", Goal::class.java)
    private val arrival = FactKey("arrived", Boolean::class.javaObjectType)
    private fun id(level: AgentLevel, value: Long) = AgentId(level, UUID(0, value))

    private inner class Fixture {
        val root = id(AgentLevel.OPERATION, 1)
        val children = linkedMapOf(
            root to listOf(id(AgentLevel.SQUAD, 2), id(AgentLevel.SQUAD, 3)),
            id(AgentLevel.SQUAD, 2) to listOf(id(AgentLevel.TEAM, 4), id(AgentLevel.TEAM, 5)),
            id(AgentLevel.SQUAD, 3) to listOf(id(AgentLevel.TEAM, 6), id(AgentLevel.TEAM, 7)),
            id(AgentLevel.TEAM, 4) to listOf(id(AgentLevel.NPC, 8), id(AgentLevel.NPC, 9)),
            id(AgentLevel.TEAM, 5) to listOf(id(AgentLevel.NPC, 10), id(AgentLevel.NPC, 11)),
            id(AgentLevel.TEAM, 6) to listOf(id(AgentLevel.NPC, 12), id(AgentLevel.NPC, 13)),
            id(AgentLevel.TEAM, 7) to listOf(id(AgentLevel.NPC, 14), id(AgentLevel.NPC, 15)),
        )
        val agents = LinkedHashMap<AgentId, PlanningAgent>()
        val positions = HashMap<AgentId, Int>()
        val goals = HashMap<AgentId, Goal>()
        val assignments = HashMap<AgentId, AssignmentView>()
        val reportSequence = HashMap<AssignmentTicket, Long>()
        val leases = ResourceLeases()
        val ledger = AssignmentLedger(
            { ticket -> agents[ticket.owner.agent]?.runtime?.isLive(ticket) == true },
            { parent, child -> child in children[parent].orEmpty() },
            { event ->
                val view = event.assignment
                val child = agents.getValue(view.ticket.child)
                when {
                    view.status == AssignmentStatus.REVOKED -> {
                        child.cancel(event.at, event.reason)
                        assignments.remove(view.ticket.child)
                    }

                    event.reason == "assigned" -> {
                        assignments[view.ticket.child] = view
                        goals[view.ticket.child] = view.task.parameters as Goal
                        child.command(view.ticket.id, view.task, facts(view.ticket.child, event.at))
                    }

                    event.reason == "parent_held" -> child.hold(event.at, view.holdReason!!)

                    event.reason == "parent_resumed" -> child.resume()
                }
            },
        )

        init {
            val all = (children.keys + children.values.flatten()).distinct()
            all.forEach { actor ->
                val descendants = children[actor].orEmpty()
                val domain = if (descendants.isEmpty()) {
                    HtnDomain(
                        actor.level,
                        listOf(
                            PrimitiveTask(task) { goal ->
                                PrimitiveSpec("move", ActionSpec(move, goal, setOf(ResourceKey(actor, "motion"))), TaskContract(completed = Condition.test(arrival, "not_arrived") { it }))
                            },
                        ),
                    )
                } else {
                    val delegate = TaskKey("child", DelegationTask::class.java)
                    HtnDomain(
                        actor.level,
                        listOf(
                            CompoundTask(task, listOf(Method("parallel", decompose = { goal, _ -> TaskNetwork(descendants.map { descendant -> NetworkNode(descendant.id.toString(), delegate.call(DelegationTask(descendant, task.call(goal)))) }) }))),
                            PrimitiveTask(delegate) { delegation -> PrimitiveSpec(delegation.child.id.toString(), ActionSpec(DelegationAction.KEY, delegation, setOf(ResourceKey(delegation.child, "command")), timeoutTicks = Long.MAX_VALUE), TaskContract()) },
                        ),
                    )
                }
                val factory = ActionRegistry(
                    listOf(
                        ActionBinding(DelegationAction.KEY) { ticket, parameters -> DelegationAction(ticket, parameters, ledger) },
                        ActionBinding(move) { _, goal ->
                            object : ActionExecution {
                                private fun update(now: Long): ActionUpdate {
                                    positions[actor] = minOf(goal.position, positions.getOrDefault(actor, 0) + 1)
                                    return if (positions.getValue(actor) >= goal.position) ActionUpdate.Completed(listOf(ActionEvidence("physical_arrival", now))) else ActionUpdate.Running
                                }
                                override fun start(now: Long) = update(now)
                                override fun tick(now: Long) = update(now)
                                override fun suspend(now: Long, reason: String) = Unit
                                override fun resume(now: Long) = Unit
                                override fun cancel(now: Long, reason: String) = Unit
                            }
                        },
                    ),
                )
                agents[actor] = PlanningAgent(actor, domain, PlanRuntime(factory, leases))
            }
        }
        fun facts(actor: AgentId, now: Long): FactSnapshot = FactSnapshot(actor, now).observe(arrival, positions.getOrDefault(actor, 0) >= (goals[actor]?.position ?: Int.MAX_VALUE))
        fun command(now: Long, goal: Int) {
            goals[root] = Goal(goal)
            agents.getValue(root).command(UUID(1, now), task.call(Goal(goal)), facts(root, now))
        }
        fun tick(now: Long) {
            agents.forEach { (actor, agent) ->
                agent.tick(facts(actor, now), planningWork = 2)
                val assignment = assignments[actor] ?: return@forEach
                val done = agent.status == PlanningStatus.COMPLETED
                val report = ChildReport(
                    assignment.ticket,
                    reportSequence.merge(assignment.ticket, 1, Long::plus)!!,
                    now,
                    if (done) AssignmentStatus.SUCCEEDED else AssignmentStatus.ACTIVE,
                    if (done) agent.runtime.reports.values.flatMap { it.evidence } else emptyList(),
                )
                ledger.report(report, now)
            }
        }
    }

    @Test fun `operation reaches goal only after squad team and NPC results propagate`() {
        val fixture = Fixture()
        fixture.command(0, 8)
        repeat(40) { fixture.tick(it.toLong()) }
        assertEquals(PlanningStatus.COMPLETED, fixture.agents.getValue(fixture.root).status)
        assertEquals(8, fixture.positions.size)
        assertTrue(fixture.positions.values.all { it == 8 })
        fixture.agents.filterKeys { it.level == AgentLevel.NPC }.keys.forEach { assertNull(fixture.leases.owner(ResourceKey(it, "motion"))) }
    }

    @Test fun `holding one squad stops its descendants while independent squad advances`() {
        val fixture = Fixture()
        fixture.command(0, 100)
        repeat(10) { fixture.tick(it.toLong()) }
        val left = id(AgentLevel.SQUAD, 2)
        val leftNpcs = (8L..11L).map { id(AgentLevel.NPC, it) }
        val before = leftNpcs.associateWith { fixture.positions.getValue(it) }
        fixture.agents.getValue(left).hold(10, "lost_cover")
        repeat(10) { fixture.tick(10L + it) }
        assertEquals(before, leftNpcs.associateWith { fixture.positions.getValue(it) })
        assertTrue((12L..15L).all { fixture.positions.getValue(id(AgentLevel.NPC, it)) > before.values.first() })
        leftNpcs.forEach { assertNull(fixture.leases.owner(ResourceKey(it, "motion"))) }
        fixture.agents.getValue(left).resume()
        repeat(5) { fixture.tick(20L + it) }
        assertTrue(leftNpcs.all { fixture.positions.getValue(it) > before.getValue(it) })
    }

    @Test fun `new root command revokes old NPC tickets throughout the hierarchy`() {
        val fixture = Fixture()
        fixture.command(0, 100)
        repeat(10) { fixture.tick(it.toLong()) }
        val npc = id(AgentLevel.NPC, 8)
        val old = fixture.agents.getValue(npc).runtime.reports.getValue("move").ticket
        fixture.command(10, 20)
        assertEquals(PlanningStatus.IDLE, fixture.agents.getValue(npc).status)
        assertNull(fixture.leases.owner(ResourceKey(npc, "motion")))
        assertFalse(fixture.agents.getValue(npc).runtime.accept(old, ActionUpdate.Completed(listOf(ActionEvidence("old_arrival", 10))), fixture.facts(npc, 10)))
        repeat(40) { fixture.tick(10L + it) }
        assertEquals(PlanningStatus.COMPLETED, fixture.agents.getValue(fixture.root).status)
        assertNotEquals(old, fixture.agents.getValue(npc).runtime.reports.getValue("move").ticket)
    }
}

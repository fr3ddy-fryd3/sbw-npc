package com.sbwnpc.squad.ai.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class HtnKernelTest {
    private val ready = FactKey("ready", Boolean::class.javaObjectType)
    private val door = FactKey("door", Boolean::class.javaObjectType)
    private val owner = PlanOwner(AgentId(AgentLevel.SQUAD, UUID(0, 1)), UUID(1, 1))
    private fun facts(now: Long) = FactSnapshot(owner.agent, now)
    private val root = TaskKey("root", Unit::class.java)
    private val set = TaskKey("set", Boolean::class.javaObjectType)
    private val consume = TaskKey("consume", Unit::class.java)
    private fun primitive(id: String, effects: Effects = Effects.NONE, condition: Condition = Condition.ALWAYS) = PrimitiveSpec(id, ActionSpec(ActionKey(id, Unit::class.java), Unit), TaskContract(start = condition), effects)
    private fun finish(session: HtnPlanningSession): PlanningResult {
        repeat(100) {
            val result = session.advance(1)
            if (result != PlanningResult.Deferred) return result
        }
        fail<Unit>("Planner never exhausted bounded work")
        return PlanningResult.Deferred
    }

    @Test fun `reported facts respect relay availability expiry and future timestamps`() {
        val delayed = facts(10).observe(ready, true, observedAt = 0, availableAt = 20, expiresAt = 40)
        assertNull(delayed.value(ready))
        assertTrue(delayed.at(20).value(ready)!!)
        assertNull(delayed.at(40).value(ready))
        assertNull(facts(10).observe(ready, true, observedAt = 20).value(ready))
    }

    @Test fun `fresh reaffirmation preserves value dependency but relevant change invalidates plan`() {
        val fact = facts(10).observe(ready, true, expiresAt = 50)
        val domain = HtnDomain(AgentLevel.SQUAD, listOf(PrimitiveTask(root) { primitive("use", condition = Condition.test(ready, "not_ready") { it }) }))
        val plan = (finish(HtnPlanningSession(domain, owner, root.call(Unit), fact)) as PlanningResult.Ready).plan
        assertTrue(plan.inputUnchanged(fact.at(20).observe(ready, true, expiresAt = 60)))
        assertTrue(plan.inputUnchanged(fact.observe(door, false)))
        assertFalse(plan.inputUnchanged(fact.observe(ready, false)))
        assertFalse(plan.inputUnchanged(fact.at(50)))
    }

    @Test fun `later failure backtracks ranked methods without leaking predictions into observed facts`() {
        val base = facts(0).observe(ready, false)
        val domain = HtnDomain(
            AgentLevel.SQUAD,
            listOf(
                CompoundTask(
                    root,
                    listOf(
                        Method("fast", score = { _, _ -> UtilityScore(0.9) }, decompose = { _, _ -> TaskNetwork.sequence(set.call(false), consume.call(Unit)) }),
                        Method("slow", score = { _, _ -> UtilityScore(0.6) }, decompose = { _, _ -> TaskNetwork.sequence(set.call(true), consume.call(Unit)) }),
                    ),
                ),
                PrimitiveTask(set) { value -> primitive("prepare", Effects.NONE.set(ready, value)) },
                PrimitiveTask(consume) { primitive("consume", condition = Condition.test(ready, "not_prepared") { it }) },
            ),
        )
        val events = ArrayList<PlanningEvent>()
        val session = HtnPlanningSession(domain, owner, root.call(Unit), base, event = events::add)
        assertEquals(PlanningResult.Deferred, session.advance(1))
        val result = finish(session) as PlanningResult.Ready
        assertTrue(result.plan.tasks.all { "/slow/" in it.node })
        assertTrue(events.any { it.verdict == Verdict.Rejected("not_prepared") })
        assertFalse(base.value(ready)!!)
    }

    @Test fun `independent tasks cannot use each others projected facts`() {
        fun domain(ordered: Boolean) =
            HtnDomain(
                AgentLevel.SQUAD,
                listOf(
                    CompoundTask(
                        root,
                        listOf(
                            Method("parallel", decompose = { _, _ ->
                                TaskNetwork(
                                    listOf(
                                        NetworkNode("producer", set.call(true)),
                                        NetworkNode("consumer", consume.call(Unit), if (ordered) setOf("producer") else emptySet()),
                                    ),
                                )
                            }),
                        ),
                    ),
                    PrimitiveTask(set) { primitive("producer", Effects.NONE.set(ready, it)) },
                    PrimitiveTask(consume) { primitive("consumer", condition = Condition.test(ready, "not_prepared") { it }) },
                ),
            )
        val base = facts(0).observe(ready, false)
        assertTrue(finish(HtnPlanningSession(domain(false), owner, root.call(Unit), base)) is PlanningResult.Failed)
        assertTrue(finish(HtnPlanningSession(domain(true), owner, root.call(Unit), base)) is PlanningResult.Ready)
    }

    @Test fun `unknown input is not reported as a forbidden method or budget deferral`() {
        val domain = HtnDomain(AgentLevel.SQUAD, listOf(CompoundTask(root, listOf(Method("known", eligible = { Condition.test(ready, "not_ready") { it } }, decompose = { _, _ -> TaskNetwork.EMPTY })))))
        assertEquals(PlanningResult.WaitingForFacts(setOf("ready")), finish(HtnPlanningSession(domain, owner, root.call(Unit), facts(0))))
        assertTrue(finish(HtnPlanningSession(domain, owner, root.call(Unit), facts(0).observe(ready, false))) is PlanningResult.Failed)
    }

    @Test fun `cyclic networks are rejected before runtime and recursive domains remain bounded`() {
        assertThrows(IllegalArgumentException::class.java) { TaskNetwork(listOf(NetworkNode("a", root.call(Unit), setOf("b")), NetworkNode("b", root.call(Unit), setOf("a")))) }
        val domain = HtnDomain(AgentLevel.SQUAD, listOf(CompoundTask(root, listOf(Method("recursive", decompose = { _, _ -> TaskNetwork.sequence(root.call(Unit)) })))))
        val result = finish(HtnPlanningSession(domain, owner, root.call(Unit), facts(0), maxExpansions = 5)) as PlanningResult.Failed
        assertTrue(result.limitReached)
    }

    @Test fun `exact expansion limit permits an already complete primitive plan`() {
        val domain = HtnDomain(AgentLevel.SQUAD, listOf(PrimitiveTask(root) { primitive("one") }))
        assertTrue(finish(HtnPlanningSession(domain, owner, root.call(Unit), facts(0), maxExpansions = 1)) is PlanningResult.Ready)
    }

    @Test fun `utility excludes illegal choices before scoring and has no execution side effects`() {
        var forbiddenEvaluations = 0
        val evaluation = UtilityEvaluation.rank(
            facts(0).observe(ready, false),
            listOf(
                DecisionCandidate("forbidden", "attack", Condition.test(ready, "no_support") { it }) {
                    forbiddenEvaluations++
                    UtilityScore(1.0)
                },
                DecisionCandidate("wait", "hold", Condition.ALWAYS) { UtilityScore.weighted(listOf(UtilityFactor("risk", 0.8, 2.0), UtilityFactor("time", 0.5))) },
            ),
        )
        assertEquals(0, forbiddenEvaluations)
        assertEquals("wait", evaluation.ranked.single().id)
        assertEquals(Verdict.Rejected("no_support"), evaluation.excluded.single().verdict)
    }

    @Test fun `planning rejects observations from another owner or domain level`() {
        val domain = HtnDomain(AgentLevel.SQUAD, listOf(PrimitiveTask(root) { primitive("use") }))
        val foreign = FactSnapshot(AgentId(AgentLevel.SQUAD, UUID(5, 5)), 0)
        assertThrows(IllegalArgumentException::class.java) { HtnPlanningSession(domain, owner, root.call(Unit), foreign) }
        assertThrows(IllegalArgumentException::class.java) {
            HtnPlanningSession(HtnDomain(AgentLevel.NPC, emptyList()), owner, root.call(Unit), facts(0))
        }
    }

    @Test fun `ancestor effects follow dependencies rather than declaration order`() {
        val domain = HtnDomain(
            AgentLevel.SQUAD,
            listOf(
                CompoundTask(
                    root,
                    listOf(
                        Method("ordered", decompose = { _, _ ->
                            TaskNetwork(
                                listOf(
                                    NetworkNode("last", set.call(true), setOf("first")),
                                    NetworkNode("first", set.call(false)),
                                    NetworkNode("use", consume.call(Unit), setOf("last")),
                                ),
                            )
                        }),
                    ),
                ),
                PrimitiveTask(set) { value -> PrimitiveSpec("write$value", ActionSpec(ActionKey("write", Boolean::class.javaObjectType), value), TaskContract(), Effects.NONE.set(ready, value)) },
                PrimitiveTask(consume) { primitive("consume", condition = Condition.test(ready, "not_prepared") { it }) },
            ),
        )
        assertTrue(finish(HtnPlanningSession(domain, owner, root.call(Unit), facts(0))) is PlanningResult.Ready)
    }

    @Test fun `conflicting independent effects cannot establish a joined precondition`() {
        val domain = HtnDomain(
            AgentLevel.SQUAD,
            listOf(
                CompoundTask(
                    root,
                    listOf(
                        Method("parallel", decompose = { _, _ ->
                            TaskNetwork(
                                listOf(
                                    NetworkNode("a", set.call(true)),
                                    NetworkNode("b", set.call(false)),
                                    NetworkNode("use", consume.call(Unit), setOf("a", "b")),
                                ),
                            )
                        }),
                    ),
                ),
                PrimitiveTask(set) { value -> PrimitiveSpec("write$value", ActionSpec(ActionKey("write", Boolean::class.javaObjectType), value), TaskContract(), Effects.NONE.set(ready, value)) },
                PrimitiveTask(consume) { primitive("consume", condition = Condition.test(ready, "not_prepared") { it }) },
            ),
        )
        val result = finish(HtnPlanningSession(domain, owner, root.call(Unit), facts(0))) as PlanningResult.Failed
        assertTrue(result.reasons.any { it == "conflicting_parallel_effects:ready" })
    }
}

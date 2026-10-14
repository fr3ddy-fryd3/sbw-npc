package com.sbwnpc.squad.ai.planning

/** Lifecycle of a planner, independent of combat/contact/terrain situation facts. */
enum class PlanningStatus { IDLE, PLANNING, EXECUTING, WAITING_FOR_FACTS, BLOCKED, COMPLETED }
data class PlanningFeedback(val owner: PlanOwner, val result: PlanningResult)

/** Keeps the current executor alive while a replacement plan is searched incrementally. */
class PlanningAgent(
    val agent: AgentId,
    private val domain: HtnDomain,
    val runtime: PlanRuntime,
    private val feedback: (PlanningFeedback) -> Unit = {},
) {
    private var owner: PlanOwner? = null
    private var root: TaskCall<*>? = null
    private var search: HtnPlanningSession? = null
    private var lastAttempt: HtnPlanningSession? = null
    private var installed: HtnPlan? = null
    private var waiting: Set<String> = emptySet()
    private var waitingSignatures: Map<String, Any?> = emptyMap()
    private var held = false
    var status = PlanningStatus.IDLE
        private set

    init {
        require(domain.level == agent.level)
    }

    fun command(order: java.util.UUID, task: TaskCall<*>, facts: FactSnapshot) {
        require(facts.owner == agent)
        val next = PlanOwner(agent, order)
        if (next == owner && task == root) return
        runtime.clear(facts.now, "new_command")
        owner = next
        root = task
        installed = null
        waiting = emptySet()
        held = false
        lastAttempt = null
        search = HtnPlanningSession(domain, next, task, facts)
        status = PlanningStatus.PLANNING
    }

    /** The tactical owner updates facts with the failure and requests repair; the kernel picks no tactic. */
    fun repair(facts: FactSnapshot) {
        require(facts.owner == agent)
        val current = owner ?: return
        search = HtnPlanningSession(domain, current, root!!, facts)
        lastAttempt = null
        waiting = emptySet()
        status = PlanningStatus.PLANNING
    }

    fun tick(facts: FactSnapshot, planningWork: Int = 4, executionWork: Int = 64) {
        require(facts.owner == agent && planningWork >= 0 && executionWork > 0)
        val current = owner ?: return
        if (held) return
        val old = installed
        if (search == null && status in setOf(PlanningStatus.EXECUTING, PlanningStatus.COMPLETED) && old != null && !old.inputUnchanged(facts)) repair(facts)
        if (search == null && waiting.isNotEmpty() && waiting.any { facts.signatureByName(it) != waitingSignatures[it] }) repair(facts)
        if (search == null && status == PlanningStatus.BLOCKED && lastAttempt?.inputUnchanged(facts) == false) repair(facts)
        if (planningWork > 0) {
            when (val result = search?.advance(planningWork)) {
                is PlanningResult.Ready -> {
                    if (runtime.install(result.plan, facts)) {
                        installed = result.plan
                        search = null
                        status = PlanningStatus.EXECUTING
                        feedback(PlanningFeedback(current, result))
                    } else {
                        repair(facts)
                    }
                }

                is PlanningResult.WaitingForFacts -> {
                    lastAttempt = search
                    search = null
                    waiting = result.facts
                    waitingSignatures = waiting.associateWith(facts::signatureByName)
                    status = PlanningStatus.WAITING_FOR_FACTS
                    feedback(PlanningFeedback(current, result))
                }

                is PlanningResult.Failed -> {
                    lastAttempt = search
                    search = null
                    status = PlanningStatus.BLOCKED
                    feedback(PlanningFeedback(current, result))
                }

                PlanningResult.Deferred, null -> Unit
            }
        }
        runtime.tick(facts, executionWork)
        if (runtime.completed && search == null && waiting.isEmpty() && status == PlanningStatus.EXECUTING) status = PlanningStatus.COMPLETED
    }

    fun hold(now: Long, reason: String) {
        held = true
        runtime.hold(now, reason)
    }

    fun resume() {
        held = false
        runtime.releaseHold()
    }

    fun cancel(now: Long, reason: String) {
        runtime.clear(now, reason)
        owner = null
        root = null
        installed = null
        search = null
        lastAttempt = null
        waiting = emptySet()
        held = false
        status = PlanningStatus.IDLE
    }
}

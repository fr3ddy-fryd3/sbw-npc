package com.sbwnpc.squad.ai.planning

import java.util.ArrayDeque

data class PlanningEvent(val node: String, val method: String?, val verdict: Verdict, val score: UtilityScore? = null)
sealed interface PlanningResult {
    data object Deferred : PlanningResult
    data class Ready(val plan: HtnPlan) : PlanningResult
    data class WaitingForFacts(val facts: Set<String>) : PlanningResult
    data class Failed(val reasons: List<String>, val limitReached: Boolean = false) : PlanningResult
}

/** Explicit DFS continuations preserve backtracking and work between budgeted updates. */
class HtnPlanningSession(
    private val domain: HtnDomain,
    private val owner: PlanOwner,
    root: TaskCall<*>,
    private val facts: FactSnapshot,
    private val maxExpansions: Int = 4096,
    private val maxDepth: Int = 64,
    private val event: (PlanningEvent) -> Unit = {},
) {
    private data class Node(val id: String, val call: TaskCall<*>, val after: Set<String>, val primitive: PrimitiveSpec? = null)
    private data class Candidate(val nodes: List<Node>, val guards: List<PlanGuard> = emptyList(), val dependencies: List<FactDependency> = emptyList())
    private val frontier = ArrayDeque<Candidate>()
    private val unknown = LinkedHashSet<String>()
    private val reasons = ArrayList<String>()
    private val readDependencies = LinkedHashSet<FactDependency>()
    private var expanded = 0
    private var result: PlanningResult? = null
    val expansions get() = expanded
    fun inputUnchanged(current: FactSnapshot): Boolean {
        require(current.owner == owner.agent)
        return readDependencies.all { it.unchanged(current) }
    }

    init {
        require(maxExpansions > 0 && maxDepth > 0)
        require(facts.owner == owner.agent && domain.level == owner.agent.level) { "Foreign planning context" }
        frontier.addLast(Candidate(listOf(Node("root", root, emptySet()))))
    }

    fun advance(work: Int): PlanningResult {
        require(work > 0)
        result?.let { return it }
        repeat(work) {
            val candidate = frontier.pollLast() ?: return finish(if (unknown.isNotEmpty()) PlanningResult.WaitingForFacts(unknown.toSet()) else PlanningResult.Failed(reasons.takeLast(32)))
            val node = candidate.nodes.firstOrNull { it.primitive == null && it.after.all { predecessor -> candidate.nodes.any { it.id == predecessor && it.primitive != null } } }
            if (node == null) {
                check(candidate.nodes.all { it.primitive != null }) { "Planner dependency cycle" }
                return finish(PlanningResult.Ready(HtnPlan(owner, candidate.nodes.map { PlannedTask(it.id, it.primitive!!, it.after) }, candidate.guards, candidate.dependencies)))
            }
            if (expanded >= maxExpansions) return finish(PlanningResult.Failed(reasons.takeLast(31) + "planning_work_limit", true))
            expanded++
            if (node.id.count { it == '/' } >= maxDepth) {
                reject(node.id, "decomposition_depth")
                return@repeat
            }
            val definition = domain.definition(node.call)
            if (definition == null) {
                reject(node.id, "undefined_task:${node.call.key.name}")
                return@repeat
            }
            val projection = projection(candidate, node) ?: return@repeat
            val view = TrackingFacts(facts.project(projection), facts, projection.values.keys)
            val resolution = definition.resolve(node.call, view)
            when (resolution) {
                is TaskResolution.Primitive -> {
                    val verdict = resolution.spec.planning.evaluate(view)
                    event(PlanningEvent(node.id, null, verdict))
                    if (verdict == Verdict.Allowed) {
                        frontier.addLast(candidate.copy(nodes = candidate.nodes.map { if (it.id == node.id) it.copy(primitive = resolution.spec) else it }, dependencies = dependencies(candidate, view)))
                    } else {
                        excluded(verdict)
                    }
                }

                is TaskResolution.Methods -> {
                    resolution.excluded.forEach { excluded ->
                        event(PlanningEvent(node.id, excluded.id, excluded.verdict))
                        excluded(excluded.verdict)
                    }
                    // Lower-ranked branches remain on the stack when a later task rejects the best.
                    for (choice in resolution.choices.asReversed()) {
                        event(PlanningEvent(node.id, choice.id, Verdict.Allowed, choice.score))
                        frontier.addLast(expand(candidate, node, choice).copy(dependencies = dependencies(candidate, view)))
                    }
                    if (resolution.choices.isEmpty() && resolution.excluded.isEmpty()) reject(node.id, "no_methods")
                }
            }
            readDependencies += view.dependencies.values
        }
        return PlanningResult.Deferred
    }

    private fun dependencies(candidate: Candidate, view: TrackingFacts) = (candidate.dependencies + view.dependencies.values).distinct()
    private fun ancestors(candidate: Candidate, node: Node): Set<String> {
        val found = LinkedHashSet<String>()
        val pending = ArrayDeque(node.after)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (found.add(next)) candidate.nodes.single { it.id == next }.after.forEach(pending::addLast)
        }
        return found
    }

    /** Independent branches cannot borrow one another's predicted effects. */
    private fun projection(candidate: Candidate, node: Node): Effects? {
        val ancestors = ancestors(candidate, node)
        val writers = LinkedHashMap<FactKey<*>, Pair<String, Any>>()
        val pending = candidate.nodes.filter { it.id in ancestors }.toMutableList()
        val ordered = ArrayList<Node>()
        while (pending.isNotEmpty()) {
            val next = pending.first { node -> node.after.none { dependency -> pending.any { it.id == dependency } } }
            ordered += next
            pending.remove(next)
        }
        for (prior in ordered) {
            for ((key, value) in prior.primitive!!.effects.values) {
                val old = writers[key]
                if (old != null && old.second != value && old.first !in ancestors(candidate, prior)) {
                    reject(node.id, "conflicting_parallel_effects:${key.name}")
                    return null
                }
                writers[key] = prior.id to value
            }
        }
        var effects = Effects.NONE
        for ((key, writer) in writers) effects = effect(effects, key, writer.second)
        return effects
    }

    @Suppress("UNCHECKED_CAST")
    private fun effect(effects: Effects, key: FactKey<*>, value: Any) = effects.set(key as FactKey<Any>, value)

    private fun expand(candidate: Candidate, old: Node, method: MethodChoice): Candidate {
        val prefix = "${old.id}/${method.id}"
        require('/' !in method.id && method.id.isNotBlank())
        val children = method.network.nodes.map { node -> Node("$prefix/${node.id}", node.call, if (node.after.isEmpty()) old.after else node.after.mapTo(LinkedHashSet()) { "$prefix/$it" }) }
        val used = children.flatMapTo(HashSet()) { it.after }
        val last = children.filter { it.id !in used }.mapTo(LinkedHashSet()) { it.id }
        val nodes = candidate.nodes.flatMap { node ->
            when {
                node.id == old.id -> children
                old.id in node.after -> listOf(node.copy(after = node.after - old.id + if (children.isEmpty()) old.after else last))
                else -> listOf(node)
            }
        }
        return Candidate(nodes, candidate.guards + PlanGuard(prefix, method.maintain), candidate.dependencies)
    }
    private fun excluded(verdict: Verdict) {
        when (verdict) {
            is Verdict.Unknown -> unknown += verdict.facts
            is Verdict.Rejected -> reasons += verdict.reason
            Verdict.Allowed -> Unit
        }
    }
    private fun reject(node: String, reason: String) {
        reasons += reason
        event(PlanningEvent(node, null, Verdict.Rejected(reason)))
    }
    private fun finish(value: PlanningResult): PlanningResult {
        result = value
        return value
    }
}

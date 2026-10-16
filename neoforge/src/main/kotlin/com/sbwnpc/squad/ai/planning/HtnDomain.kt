package com.sbwnpc.squad.ai.planning

import java.util.UUID

enum class AgentLevel { OPERATION, SQUAD, TEAM, NPC }
data class AgentId(val level: AgentLevel, val id: UUID)
data class PlanOwner(val agent: AgentId, val order: UUID)
data class TaskKey<P : Any>(val name: String, val type: Class<P>) {
    init {
        require(name.isNotBlank())
    }
    fun call(parameters: P) = TaskCall(this, parameters)
}
data class TaskCall<P : Any>(val key: TaskKey<P>, val parameters: P) {
    init {
        require(key.type.isInstance(parameters))
    }
}
data class ResourceKey(val agent: AgentId, val channel: String) {
    init {
        require(channel.isNotBlank())
    }
}

data class TaskContract(val start: Condition = Condition.ALWAYS, val maintain: Condition = Condition.ALWAYS, val completed: Condition = Condition.ALWAYS)
data class ActionKey<P : Any>(val name: String, val type: Class<P>) {
    init {
        require(name.isNotBlank())
    }
}
data class ActionSpec<P : Any>(val key: ActionKey<P>, val parameters: P, val resources: Set<ResourceKey> = emptySet(), val priority: Int = 0, val timeoutTicks: Long = 600) {
    init {
        require(key.type.isInstance(parameters) && timeoutTicks > 0)
    }
}
data class PrimitiveSpec(val identity: String, val action: ActionSpec<*>, val contract: TaskContract, val effects: Effects = Effects.NONE, val planning: Condition = contract.start) {
    init {
        require(identity.isNotBlank())
    }
}

/** Dependencies describe execution order, including fork/join; construction rejects cycles. */
data class NetworkNode(val id: String, val call: TaskCall<*>, val after: Set<String> = emptySet()) {
    init {
        require(id.isNotBlank() && '/' !in id)
    }
}
class TaskNetwork(nodes: List<NetworkNode>) {
    val nodes = nodes.toList()
    init {
        require(nodes.map { it.id }.distinct().size == nodes.size) { "Duplicate network node" }
        val ids = nodes.mapTo(HashSet()) { it.id }
        require(nodes.all { it.after.all(ids::contains) && it.id !in it.after }) { "Missing or self dependency" }
        val remaining = nodes.associateByTo(LinkedHashMap()) { it.id }
        val visited = HashSet<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { visited.containsAll(it.after) }
            require(ready.isNotEmpty()) { "Cyclic task network" }
            ready.forEach {
                visited += it.id
                remaining.remove(it.id)
            }
        }
    }
    companion object {
        val EMPTY = TaskNetwork(emptyList())
        fun sequence(vararg calls: TaskCall<*>) = TaskNetwork(calls.mapIndexed { index, call -> NetworkNode(index.toString(), call, if (index == 0) emptySet() else setOf((index - 1).toString())) })
    }
}

data class MethodChoice(val id: String, val network: TaskNetwork, val score: UtilityScore = UtilityScore.NEUTRAL, val maintain: Condition = Condition.ALWAYS)
data class Method<P : Any>(val id: String, val eligible: (P) -> Condition = { Condition.ALWAYS }, val score: (P, FactView) -> UtilityScore = { _, _ -> UtilityScore.NEUTRAL }, val decompose: (P, FactView) -> TaskNetwork, val maintain: (P) -> Condition = { Condition.ALWAYS })

sealed interface TaskResolution {
    data class Primitive(val spec: PrimitiveSpec) : TaskResolution
    data class Methods(val choices: List<MethodChoice>, val excluded: List<ExcludedDecision>) : TaskResolution
}

sealed class TaskDefinition<P : Any>(val key: TaskKey<P>) {
    protected abstract fun resolve(parameters: P, facts: FactView): TaskResolution
    internal fun resolve(call: TaskCall<*>, facts: FactView): TaskResolution {
        require(key == call.key && key.type.isInstance(call.parameters))
        return resolve(key.type.cast(call.parameters), facts)
    }
}
class PrimitiveTask<P : Any>(key: TaskKey<P>, private val describe: (P) -> PrimitiveSpec) : TaskDefinition<P>(key) {
    override fun resolve(parameters: P, facts: FactView) = TaskResolution.Primitive(describe(parameters))
}
class CompoundTask<P : Any>(key: TaskKey<P>, methods: List<Method<P>>) : TaskDefinition<P>(key) {
    private val methods = methods.toList()
    init {
        require(methods.isNotEmpty() && methods.map { it.id }.distinct().size == methods.size)
        require(methods.all { it.id.isNotBlank() && '/' !in it.id })
    }
    override fun resolve(parameters: P, facts: FactView): TaskResolution {
        val choices = ArrayList<MethodChoice>()
        val excluded = ArrayList<ExcludedDecision>()
        for (method in methods) {
            when (val verdict = method.eligible(parameters).evaluate(facts)) {
                Verdict.Allowed -> choices += MethodChoice(method.id, method.decompose(parameters, facts), method.score(parameters, facts), method.maintain(parameters))
                else -> excluded += ExcludedDecision(method.id, verdict)
            }
        }
        return TaskResolution.Methods(choices.sortedWith(compareByDescending<MethodChoice> { it.score.value }.thenBy { it.id }), excluded)
    }
}

class HtnDomain(val level: AgentLevel, definitions: List<TaskDefinition<*>>) {
    private val definitions = definitions.associateBy { it.key }
    init {
        require(this.definitions.size == definitions.size)
        require(definitions.map { it.key.name }.distinct().size == definitions.size)
    }
    internal fun definition(call: TaskCall<*>) = definitions[call.key]
}

data class PlannedTask(val node: String, val spec: PrimitiveSpec, val after: Set<String>)
data class PlanGuard(val scope: String, val condition: Condition)
data class HtnPlan(val owner: PlanOwner, val tasks: List<PlannedTask>, val guards: List<PlanGuard>, val dependencies: List<FactDependency>) {
    init {
        require(tasks.map { it.spec.identity }.distinct().size == tasks.size) { "Duplicate execution identity" }
        require(tasks.map { it.node }.distinct().size == tasks.size) { "Duplicate execution node" }
        val ids = tasks.mapTo(HashSet()) { it.node }
        require(tasks.all { it.node.isNotBlank() && it.node !in it.after && ids.containsAll(it.after) }) { "Invalid execution dependency" }
        val pending = tasks.associateByTo(LinkedHashMap()) { it.node }
        val visited = HashSet<String>()
        while (pending.isNotEmpty()) {
            val ready = pending.values.filter { visited.containsAll(it.after) }
            require(ready.isNotEmpty()) { "Cyclic execution graph" }
            ready.forEach {
                visited += it.node
                pending.remove(it.node)
            }
        }
    }
    fun inputUnchanged(facts: FactSnapshot) = dependencies.all { it.unchanged(facts) }
}

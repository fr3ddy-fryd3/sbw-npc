package com.sbwnpc.squad.ai.planning

import java.util.UUID

data class ExecutionTicket(val owner: PlanOwner, val identity: String, val generation: Long, val session: UUID)
data class ActionEvidence(val kind: String, val observedAt: Long, val detail: String = "") {
    init {
        require(kind.isNotBlank())
    }
}
sealed interface ActionUpdate {
    data object Running : ActionUpdate
    data class Deferred(val reason: String) : ActionUpdate
    data class Completed(val evidence: List<ActionEvidence>) : ActionUpdate
    data class Failed(val reason: String) : ActionUpdate
}
interface ActionExecution {
    fun start(now: Long): ActionUpdate
    fun tick(now: Long): ActionUpdate
    fun suspend(now: Long, reason: String)
    fun resume(now: Long)
    fun cancel(now: Long, reason: String)
    fun dispose(now: Long, reason: String) = Unit

    /** Synchronous adapters may revise intent in place; asynchronous actions use a new execution. */
    fun revise(spec: ActionSpec<*>, now: Long): Boolean = false
}
fun interface ActionFactory {
    fun create(ticket: ExecutionTicket, spec: ActionSpec<*>): ActionExecution
}
enum class ExecutionStatus { PENDING, RUNNING, SUSPENDED, COMPLETED, FAILED, CANCELLED }
data class ExecutionReport(val ticket: ExecutionTicket, val status: ExecutionStatus, val reason: String?, val evidence: List<ActionEvidence>, val activeTicks: Long)
data class RuntimeEvent(val report: ExecutionReport)

/** Atomically acquired leases prevent partial reservations and resource-order deadlock. */
class ResourceLeases {
    private data class Request(val resources: Set<ResourceKey>, val priority: Int, val since: Long)
    private val held = LinkedHashMap<ResourceKey, ExecutionTicket>()
    private val waiting = LinkedHashMap<ExecutionTicket, Request>()
    fun acquire(ticket: ExecutionTicket, resources: Set<ResourceKey>, priority: Int, now: Long): Boolean {
        if (resources.all { held[it] == ticket }) return true
        waiting.putIfAbsent(ticket, Request(resources.toSet(), priority, now))
        val earlier = waiting.entries.firstOrNull { (other, request) ->
            other != ticket && request.resources.any { it in resources } &&
                (
                    request.priority + (now - request.since) / 20.0 > priority + (now - waiting.getValue(ticket).since) / 20.0 ||
                        (request.priority == priority && request.since <= waiting.getValue(ticket).since && waiting.keys.indexOf(other) < waiting.keys.indexOf(ticket))
                    )
        }
        if (earlier != null || resources.any { held[it] != null && held[it] != ticket }) return false
        resources.forEach { held[it] = ticket }
        waiting.remove(ticket)
        return true
    }
    fun release(ticket: ExecutionTicket) {
        held.entries.removeIf { it.value == ticket }
        waiting.remove(ticket)
    }
    fun owner(resource: ResourceKey): ExecutionTicket? = held[resource]
    val waitingCount get() = waiting.size
}

/** Owns execution state, independent of plan decomposition and per-frame world observations. */
class PlanRuntime(
    private val actions: ActionFactory,
    private val leases: ResourceLeases,
    private val event: (RuntimeEvent) -> Unit = {},
) {
    private class Entry(var task: PlannedTask, val ticket: ExecutionTicket) {
        var status = ExecutionStatus.PENDING
        var reason: String? = null
        var instance: ActionExecution? = null
        var started = false
        var acceptsExternal = true
        var evidence: List<ActionEvidence> = emptyList()
        var activeTicks = 0L
        var lastTick: Long? = null
        var lastPolledAt: Long? = null
        fun report() = ExecutionReport(ticket, status, reason, evidence, activeTicks)
    }
    private val session = UUID.randomUUID()
    private var generation = 0L
    private var plan: HtnPlan? = null
    private val entries = LinkedHashMap<String, Entry>()
    private var held = false
    private var cursor = 0
    private var byNode: Map<String, Entry> = emptyMap()
    val reports get() = entries.mapValues { it.value.report() }
    val completed get() = plan != null && entries.values.all { it.status == ExecutionStatus.COMPLETED }

    fun isLive(ticket: ExecutionTicket): Boolean =
        entries[ticket.identity]?.let {
            it.ticket == ticket && it.status !in setOf(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)
        } == true

    fun hold(now: Long, reason: String) {
        held = true
        entries.values.filter { it.status !in setOf(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED) }.forEach { suspend(it, now, reason) }
    }

    fun releaseHold() {
        held = false
    }

    fun install(next: HtnPlan, facts: FactSnapshot): Boolean {
        require(facts.owner == next.owner.agent) { "Foreign execution context" }
        if (!next.inputUnchanged(facts)) return false
        val desired = next.tasks.associateBy { it.spec.identity }
        require(next.tasks.map { it.node }.distinct().size == next.tasks.size)
        require(next.tasks.all { task -> task.after.all { dependency -> next.tasks.any { it.node == dependency } } })
        for ((id, entry) in entries.toMap()) {
            val replacement = desired[id]
            val actionCompatible = replacement != null && (
                replacement.spec.action == entry.task.spec.action ||
                    (
                        next.owner == entry.ticket.owner && entry.status in setOf(ExecutionStatus.RUNNING, ExecutionStatus.SUSPENDED) &&
                            replacement.spec.action.key == entry.task.spec.action.key && replacement.spec.action.resources == entry.task.spec.action.resources &&
                            replacement.spec.action.priority == entry.task.spec.action.priority && replacement.spec.action.timeoutTicks == entry.task.spec.action.timeoutTicks &&
                            entry.instance?.revise(replacement.spec.action, facts.now) == true
                        )
                )
            val compatible = next.owner == entry.ticket.owner && actionCompatible &&
                entry.status !in setOf(ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)
            val stillComplete = entry.status != ExecutionStatus.COMPLETED || replacement?.spec?.contract?.completed?.evaluate(facts) == Verdict.Allowed
            if (!compatible || !stillComplete) {
                cancel(entry, facts.now, "assignment_changed")
                entries.remove(id)
            } else {
                if (replacement!!.spec.action != entry.task.spec.action) entry.acceptsExternal = false
                entry.task = replacement
            }
        }
        for (task in next.tasks) entries.getOrPut(task.spec.identity) { Entry(task, ExecutionTicket(next.owner, task.spec.identity, ++generation, session)) }
        byNode = entries.values.associateBy { it.task.node }
        plan = next
        return true
    }

    fun clear(now: Long, reason: String) {
        entries.values.forEach { cancel(it, now, reason) }
        entries.clear()
        byNode = emptyMap()
        plan = null
        held = false
    }

    fun tick(facts: FactSnapshot, work: Int = 64) {
        require(work > 0)
        val current = plan ?: return
        require(facts.owner == current.owner.agent) { "Foreign execution context" }
        if (held) return
        val list = entries.values.toList()
        if (list.isEmpty()) return
        val guards = current.guards.associateWith { it.condition.evaluate(facts) }
        repeat(minOf(work, list.size)) {
            val entry = list[Math.floorMod(cursor++, list.size)]
            if (entry.status in setOf(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)) return@repeat
            val predecessors = entry.task.after.map { node -> byNode.getValue(node) }
            if (predecessors.any { it.status in setOf(ExecutionStatus.FAILED, ExecutionStatus.CANCELLED) }) {
                suspend(entry, facts.now, "dependency_failed")
                return@repeat
            }
            if (predecessors.any { it.status != ExecutionStatus.COMPLETED }) {
                suspend(entry, facts.now, "dependency_pending")
                return@repeat
            }
            val guard = guards.entries.firstOrNull { (guard, verdict) -> entry.task.node.startsWith("${guard.scope}/") && verdict != Verdict.Allowed }?.value
            val readiness = guard ?: entry.task.spec.contract.maintain.evaluate(facts).takeIf { it != Verdict.Allowed }
                ?: if (!entry.started) entry.task.spec.contract.start.evaluate(facts).takeIf { it != Verdict.Allowed } else null
            if (readiness != null) {
                suspend(entry, facts.now, reason(readiness))
                return@repeat
            }
            if (!leases.acquire(entry.ticket, entry.task.spec.action.resources, entry.task.spec.action.priority, facts.now)) {
                suspend(entry, facts.now, "resource_wait", release = false)
                return@repeat
            }
            if (entry.lastPolledAt == facts.now) return@repeat
            if (entry.status == ExecutionStatus.SUSPENDED && entry.started) entry.instance!!.resume(facts.now)
            if (entry.status == ExecutionStatus.RUNNING && entry.reason?.startsWith("deferred:") != true) entry.activeTicks += facts.now - (entry.lastTick ?: facts.now)
            entry.lastTick = facts.now
            entry.lastPolledAt = facts.now
            if (entry.activeTicks >= entry.task.spec.action.timeoutTicks) {
                fail(entry, facts.now, "active_timeout")
                return@repeat
            }
            val update = if (!entry.started) {
                entry.instance = actions.create(entry.ticket, entry.task.spec.action)
                entry.started = true
                entry.instance!!.start(facts.now)
            } else {
                entry.instance!!.tick(facts.now)
            }
            apply(entry, update, facts)
        }
    }

    /** Delayed completions may only update the exact live execution which issued the request. */
    fun accept(ticket: ExecutionTicket, update: ActionUpdate, facts: FactSnapshot): Boolean {
        val entry = entries[ticket.identity] ?: return false
        if (entry.ticket != ticket || !entry.acceptsExternal || !entry.started || entry.status !in setOf(ExecutionStatus.RUNNING, ExecutionStatus.SUSPENDED)) return false
        val current = plan ?: return false
        if (held) return false
        require(facts.owner == current.owner.agent) { "Foreign execution context" }
        if (entry.task.spec.contract.maintain.evaluate(facts) != Verdict.Allowed || current.guards.any { guard -> entry.task.node.startsWith("${guard.scope}/") && guard.condition.evaluate(facts) != Verdict.Allowed }) return false
        apply(entry, update, facts)
        return true
    }

    private fun apply(entry: Entry, update: ActionUpdate, facts: FactSnapshot) {
        when (update) {
            ActionUpdate.Running -> change(entry, ExecutionStatus.RUNNING, null)

            is ActionUpdate.Deferred -> change(entry, ExecutionStatus.RUNNING, "deferred:${update.reason}")

            is ActionUpdate.Failed -> fail(entry, facts.now, update.reason)

            is ActionUpdate.Completed -> {
                if (update.evidence.isEmpty() || update.evidence.any { it.observedAt > facts.now }) {
                    fail(entry, facts.now, "invalid_completion_evidence")
                } else {
                    when (val verdict = entry.task.spec.contract.completed.evaluate(facts)) {
                        Verdict.Allowed -> {
                            entry.evidence = update.evidence.toList()
                            leases.release(entry.ticket)
                            change(entry, ExecutionStatus.COMPLETED, null)
                        }

                        else -> suspend(entry, facts.now, "unconfirmed_completion:${reason(verdict)}")
                    }
                }
            }
        }
    }
    private fun suspend(entry: Entry, now: Long, reason: String, release: Boolean = true) {
        if (entry.started && entry.status == ExecutionStatus.RUNNING) entry.instance!!.suspend(now, reason)
        if (release) leases.release(entry.ticket)
        entry.lastTick = null
        change(entry, ExecutionStatus.SUSPENDED, reason)
    }
    private fun fail(entry: Entry, now: Long, reason: String) {
        if (entry.started) entry.instance!!.cancel(now, reason)
        leases.release(entry.ticket)
        change(entry, ExecutionStatus.FAILED, reason)
    }
    private fun cancel(entry: Entry, now: Long, reason: String) {
        if (entry.started && entry.status !in setOf(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)) entry.instance!!.cancel(now, reason)
        if (entry.started) entry.instance!!.dispose(now, reason)
        leases.release(entry.ticket)
        change(entry, ExecutionStatus.CANCELLED, reason)
    }
    private fun change(entry: Entry, status: ExecutionStatus, reason: String?) {
        if (entry.status == status && entry.reason == reason) return
        entry.status = status
        entry.reason = reason
        event(RuntimeEvent(entry.report()))
    }
    private fun reason(verdict: Verdict) =
        when (verdict) {
            Verdict.Allowed -> "allowed"
            is Verdict.Rejected -> verdict.reason
            is Verdict.Unknown -> "unknown:${verdict.facts.sorted().joinToString(",")}"
        }
}

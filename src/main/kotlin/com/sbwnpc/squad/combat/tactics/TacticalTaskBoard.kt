package com.sbwnpc.squad.combat.tactics

import java.util.Collections
import java.util.UUID

/** The only owner of assignments. A retained task keeps its path/search/progress identity. */
class TacticalTaskBoard(private val plan: Int, private val events: TacticalEventSink) {
    private val active = LinkedHashMap<UUID, TacticalTask>()
    private val failed = HashSet<UUID>()
    val tasks: Map<UUID, TacticalTask> = Collections.unmodifiableMap(active)
    val failedMembers: Set<UUID> = Collections.unmodifiableSet(failed)

    fun replace(desired: Map<UUID, TacticalTask>, now: Long, trigger: String, retainCover: Boolean = false) {
        require(desired.values.all { it.plan == plan }) { "Assignments must belong to plan $plan" }
        val next = desired.filterKeys { it !in failed }.toMutableMap()
        if (retainCover) for ((id, old) in active) {
            if (old.job == TacticalJob.COVER && next[id]?.job == TacticalJob.COVER) next[id] = old
        }
        for ((id, old) in active.toMap()) if (next[id] !== old) release(id, now, trigger)
        for ((id, task) in next) if (active[id] !== task) put(id, task, now, trigger)
    }

    fun put(id: UUID, task: TacticalTask, now: Long, trigger: String) {
        if (id in failed || active[id] === task) return
        require(task.plan == plan) { "Task belongs to plan ${task.plan}, expected $plan" }
        release(id, now, trigger)
        active[id] = task
        events.emit(TacticalEvent.TaskAssigned(plan, now, id, task.job, task.anchor, trigger))
    }

    fun prune(alive: Set<UUID>, now: Long) {
        for (id in active.keys.toList()) if (id !in alive) release(id, now, "member_unavailable")
    }

    fun abandon(id: UUID, now: Long, trigger: String) {
        failed.add(id)
        release(id, now, trigger)
    }

    fun clear(now: Long, trigger: String) {
        for (id in active.keys.toList()) release(id, now, trigger)
    }

    private fun release(id: UUID, now: Long, trigger: String) {
        val old = active.remove(id) ?: return
        events.emit(TacticalEvent.TaskReleased(plan, now, id, old, trigger))
    }
}

package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import java.util.WeakHashMap

/** The oldest optional search owns a reserved turn; sight and shot checks cannot starve it. */
object TickBudget {
    enum class Work { POSITIONS, LOCAL_FIRE, COVER }
    data class SearchResult<T>(val value: T)
    private val worlds = WeakHashMap<ServerLevel, RaycastWorkBudget>()
    private var active: RaycastWorkBudget? = null

    /** Null means deferred; a completed search may itself legitimately return null. */
    fun <T> attempt(entity: NpcEntity, work: Work, operation: () -> T): SearchResult<T>? = search(entity, work, null as SearchResult<T>?) { SearchResult(operation()) }

    /** Each task keeps its own cursor when its bounded turn ends. No ray result is fabricated. */
    fun <T> search(entity: NpcEntity, work: Work, deferred: T, operation: () -> T): T {
        val level = entity.level() as? ServerLevel ?: return deferred
        check(active == null) { "Raycast searches must not nest" }
        val budget = worlds.getOrPut(level) { RaycastWorkBudget() }
        val key = RaycastWorkBudget.Key(entity.uuid, work)
        val waited = budget.waited(key, level.gameTime)
        if (!budget.acquire(key, level.gameTime)) return deferred
        if (waited >= 20) {
            DebugFlags.log(LogGroup.PATH, "[tactics] {} event=ray_budget_granted work={} waited={} queued={}", entity.uuid, work, waited, budget.waitingCount)
        }
        active = budget
        try {
            return operation()
        } finally {
            active = null
        }
    }

    fun hasRaycasts(level: ServerLevel): Boolean = active?.available(level.gameTime) ?: true

    // Detection, the final shot gate and other constant-size checks still execute their real
    // rays. Counting them against optional work caused late entities to wait forever.
    fun chargeRaycast(level: ServerLevel) {
        active?.charge(level.gameTime)
    }
}

/** Reserve capacity for the oldest request, while other ready actors can use the remainder. */
internal class RaycastWorkBudget {
    data class Key(val actor: UUID, val work: TickBudget.Work)
    private data class Request(val since: Long, var seenAt: Long)
    private val waiting = LinkedHashMap<Key, Request>()
    private val served = HashSet<Key>()
    private var tick = Long.MIN_VALUE
    private var used = 0
    private var turnUsed = 0
    private var turns = 0
    private var priority: Key? = null
    private var priorityTurn = false
    private var reserved = emptySet<Key>()
    val waitingCount get() = waiting.size

    fun waited(key: Key, now: Long): Long = waiting[key]?.let { now - it.since } ?: 0

    private fun roll(now: Long) {
        if (tick == now) return
        tick = now
        used = 0
        turns = 0
        served.clear()
        waiting.entries.removeIf { now - it.value.seenAt > 6 }
        priority = waiting.keys.firstOrNull()
        reserved = waiting.keys.take(8).toSet()
    }

    fun acquire(key: Key, now: Long): Boolean {
        roll(now)
        if (key in served) return false
        waiting.getOrPut(key) { Request(now, now) }.seenAt = now
        val reservedTurn = key == priority
        if (reserved.isNotEmpty() && key !in reserved) return false
        // A synchronous cover scan is indivisible. It may only use the reserved turn,
        // otherwise an early suppressed actor could consume another actor's reservation.
        if (key.work == TickBudget.Work.COVER && priority != null && !reservedTurn) return false
        if (turns >= 32 || (!reservedTurn && used >= if (priority == null) 500 else 436)) return false
        waiting.remove(key)
        served += key
        turns++
        turnUsed = 0
        priorityTurn = reservedTurn
        if (reservedTurn) priority = null
        return true
    }

    fun available(now: Long): Boolean {
        roll(now)
        return turnUsed < 64 && (priorityTurn || used < if (priority == null) 500 else 436)
    }

    // Soft limits finish the current indivisible candidate, then defer the next candidate.
    fun charge(now: Long) {
        roll(now)
        used++
        turnUsed++
    }
}

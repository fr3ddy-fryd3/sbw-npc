package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import java.util.WeakHashMap

/** Shared by tactical posts, individual firing shifts and cover; each world has its own queue. */
object TacticalBudget {
    private val worlds = WeakHashMap<ServerLevel, TacticalPathBudget>()

    fun path(entity: NpcEntity): Boolean {
        val level = entity.level() as? ServerLevel ?: return false
        val budget = worlds.getOrPut(level) { TacticalPathBudget() }
        val waited = budget.waited(entity.uuid, level.gameTime)
        val granted = budget.acquire(entity.uuid, level.gameTime)
        if (granted && waited >= 20) {
            DebugFlags.log(LogGroup.PATH, "[tactics] {} event=path_budget_granted waited={} queued={}", entity.uuid, waited, budget.waitingCount)
        }
        return granted
    }

    internal fun <T> measure(entity: NpcEntity, operation: () -> T): T {
        val level = entity.level() as? ServerLevel ?: return operation()
        val started = System.nanoTime()
        try {
            return operation()
        } finally {
            worlds.getOrPut(level) { TacticalPathBudget() }.charge(level.gameTime, System.nanoTime() - started)
        }
    }
}

/** FIFO reservations survive callers' tick order. Abandoned requests expire after six ticks. */
internal class TacticalPathBudget {
    private data class Request(val since: Long, var seenAt: Long)
    private val waiting = LinkedHashMap<UUID, Request>()
    private var reserved = mutableSetOf<UUID>()
    private val served = HashSet<UUID>()
    private var tick = Long.MIN_VALUE
    private var paths = 0
    private var nanos = 0L
    val waitingCount get() = waiting.size

    fun waited(id: UUID, now: Long): Long = waiting[id]?.let { now - it.since } ?: 0

    private fun roll(now: Long) {
        if (tick != now) {
            tick = now
            paths = 0
            nanos = 0
            served.clear()
            waiting.entries.removeIf { now - it.value.seenAt > 6 }
            reserved = waiting.keys.take(4).toMutableSet()
        }
    }

    fun acquire(id: UUID, now: Long): Boolean {
        roll(now)
        if (id in served) return false
        waiting.getOrPut(id) { Request(now, now) }.seenAt = now
        if (paths >= 4 || nanos >= 8_000_000) return false
        if (reserved.isNotEmpty() && id !in reserved) return false
        if (reserved.isEmpty() && waiting.keys.first() != id) return false
        waiting.remove(id)
        reserved.remove(id)
        served += id
        paths++
        return true
    }

    /** The current A* is synchronous; this soft limit prevents starting another after an overrun. */
    fun charge(now: Long, elapsedNanos: Long) {
        roll(now)
        nanos += elapsedNanos
    }
}

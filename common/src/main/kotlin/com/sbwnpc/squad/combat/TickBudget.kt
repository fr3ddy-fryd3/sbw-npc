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

    /**
     * Each task keeps its own cursor when its bounded turn ends. No ray result is fabricated.
     * A result for which [unfinished] holds keeps the search's place in the queue for its next turn.
     */
    fun <T> search(entity: NpcEntity, work: Work, deferred: T, unfinished: (T) -> Boolean = { false }, operation: () -> T): T {
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
        var keep = false
        try {
            return operation().also { keep = unfinished(it) }
        } finally {
            active = null
            budget.release(key, keep)
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
    private data class Request(var since: Long, var seenAt: Long, var heldTurns: Int = 0, var every: Long = 1)
    private val waiting = LinkedHashMap<Key, Request>()

    // When each search last asked, kept past its finished turn: its next request asks at its own pace.
    private val lastAsk = HashMap<Key, Long>()
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
        lastAsk.values.removeIf { now - it > 6 }
        // Places go to the oldest searches due to ask this tick (#312): a man asks only when his brain
        // ticks, every fourth tick with no player near, and of eight places held by the oldest about
        // two asked on any tick while nobody else might take a turn and the queue waited 28-48 ticks.
        val due = waiting.entries.filter { now - it.value.seenAt >= it.value.every }.map { it.key }
        priority = due.firstOrNull()
        reserved = due.take(8).toSet()
    }

    /** Notes that [key] asked at [now], and so how often it asks. */
    private fun asked(key: Key, now: Long) {
        val previous = lastAsk.put(key, now)
        val request = waiting.getOrPut(key) { Request(now, now) }
        if (previous != null && previous != now) request.every = now - previous
        request.seenAt = now
    }

    /** Whether a turn is left for [key] beyond the places still held for the oldest, a full turn's rays a place. */
    private fun leftBeyondHeld(key: Key, limit: Int): Boolean {
        val held = reserved.count { it != key && it !in served }
        return turns + held < MAX_TURNS && used + held * TURN_RAYS < limit
    }

    fun acquire(key: Key, now: Long): Boolean {
        roll(now)
        if (key in served) return false
        asked(key, now)
        val reservedTurn = key == priority
        val limit = if (priority == null) 500 else 436
        if (key !in reserved && !leftBeyondHeld(key, limit)) return false
        // A synchronous cover scan is indivisible. It may only use the reserved turn,
        // otherwise an early suppressed actor could consume another actor's reservation.
        if (key.work == TickBudget.Work.COVER && priority != null && !reservedTurn) return false
        if (turns >= MAX_TURNS || (!reservedTurn && used >= limit)) return false
        served += key
        turns++
        turnUsed = 0
        priorityTurn = reservedTurn
        if (reservedTurn) priority = null
        return true
    }

    /**
     * Ends a granted turn. A finished search leaves the queue; an unfinished one keeps its place, so
     * the oldest searches finish first. Sent back to the tail after every turn, 300 searches started
     * together all advanced a little each turn and none finished for 100-200 ticks (#301).
     *
     * The place is kept for [HELD_TURNS] turns at most: in a playtest a man in a hole whose routes
     * kept being rejected held a reserved slot for 1000 ticks, and a few such men left the rest of
     * the queue one or two slots.
     */
    fun release(key: Key, unfinished: Boolean) {
        val request = waiting[key] ?: return
        if (!unfinished || request.heldTurns >= HELD_TURNS) {
            waiting.remove(key)
            return
        }
        request.heldTurns++
        // The wait is counted again from this turn, so the trace reports real waits only.
        request.since = tick
    }

    private companion object {
        /** About two full candidate collections of an ordinary post search. */
        const val HELD_TURNS = 10

        /** Turns one tick grants at most. */
        const val MAX_TURNS = 32

        /** Rays one turn may use. */
        const val TURN_RAYS = 64
    }

    fun available(now: Long): Boolean {
        roll(now)
        return turnUsed < TURN_RAYS && (priorityTurn || used < if (priority == null) 500 else 436)
    }

    // Soft limits finish the current indivisible candidate, then defer the next candidate.
    fun charge(now: Long) {
        roll(now)
        used++
        turnUsed++
    }
}

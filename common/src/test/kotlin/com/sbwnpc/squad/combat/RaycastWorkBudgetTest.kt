package com.sbwnpc.squad.combat

import com.sbwnpc.squad.combat.tactics.TacticalPathBudget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RaycastWorkBudgetTest {
    @Test fun `independent path admission completes scored work without awaiting another ray turn`() {
        val rays = RaycastWorkBudget()
        val paths = TacticalPathBudget()
        val actors = (0L..159L).map { UUID(1, it) }
        val turns = actors.associateWith { mutableListOf<Long>() }
        val pending = HashSet<UUID>()
        for (tick in 0L..255L) {
            var searches = 0
            for (actor in actors) {
                if (actor in pending) {
                    if (paths.acquire(actor, tick)) {
                        pending -= actor
                        turns.getValue(actor) += tick
                        paths.charge(tick, 1_000_000)
                        searches++
                    }
                    continue
                }
                if (!rays.take(RaycastWorkBudget.Key(actor, TickBudget.Work.POSITIONS), tick)) continue
                repeat(32) { rays.charge(tick) }
                if (paths.acquire(actor, tick)) {
                    turns.getValue(actor) += tick
                    paths.charge(tick, 1_000_000)
                    searches++
                } else {
                    pending += actor
                }
            }
            assertTrue(searches <= 4)
        }
        assertTrue(turns.values.all { it.size >= 2 }, "A path request must not expire or block all successors while awaiting the raycast queue: ${turns.filterValues { it.size < 2 }.mapValues { it.value.size }}")
    }

    @Test fun `late actors receive turns despite fixed iteration order and independent work`() {
        val budget = RaycastWorkBudget()
        val work = (0L..159L).flatMap { actor ->
            listOf(TickBudget.Work.POSITIONS, TickBudget.Work.LOCAL_FIRE).map { RaycastWorkBudget.Key(UUID(1, actor), it) }
        }
        val turns = work.associateWith { mutableListOf<Long>() }
        for (tick in 0L..119L) {
            for (key in work) {
                if (!budget.take(key, tick)) continue
                turns.getValue(key) += tick
                repeat(64) { budget.charge(tick) }
                assertFalse(budget.available(tick))
                assertFalse(budget.take(key, tick), "One actor cannot consume another turn")
            }
        }
        assertTrue(turns.values.all { it.size >= 3 }, "Every work item must receive at least three bounded turns")
        assertTrue(turns.values.all { samples -> samples.first() <= 40 && samples.zipWithNext().all { (a, b) -> b - a <= 40L } })
    }

    @Test fun `an indivisible cover scan can overrun but cannot launch another scan that tick`() {
        val budget = RaycastWorkBudget()
        assertTrue(budget.take(RaycastWorkBudget.Key(UUID(1, 0), TickBudget.Work.COVER), 0))
        repeat(510) { budget.charge(0) }
        val waiting = RaycastWorkBudget.Key(UUID(1, 1), TickBudget.Work.POSITIONS)
        assertFalse(budget.take(waiting, 0))
        assertTrue(budget.take(waiting, 1))
        assertTrue(budget.available(1))
    }

    @Test fun `disappeared work expires and another world has an independent queue`() {
        val budget = RaycastWorkBudget()
        val active = RaycastWorkBudget.Key(UUID(1, 100), TickBudget.Work.POSITIONS)
        for (actor in 0L..32L) budget.take(RaycastWorkBudget.Key(UUID(1, actor), TickBudget.Work.COVER), 0)
        assertFalse(budget.take(active, 0))
        assertTrue(budget.take(active, 1), "Ready work within the reserved group can pass an absent actor")
        // The absent actor's place is kept for him; what is left of the tick is anyone's (#312).
        val fresh = (200L..231L).map { RaycastWorkBudget.Key(UUID(1, it), TickBudget.Work.POSITIONS) }
        assertTrue(fresh.take(31).all { budget.take(it, 2) })
        assertFalse(budget.take(fresh.last(), 2), "New work took the place kept for a waiting actor")
        assertTrue(fresh.all { budget.take(it, 7) }, "A disappeared actor kept his place")
        assertTrue(RaycastWorkBudget().take(active, 2))
    }

    @Test fun `one expensive early actor cannot repeatedly overtake waiting cheap actors`() {
        val budget = RaycastWorkBudget()
        val early = RaycastWorkBudget.Key(UUID(1, 0), TickBudget.Work.COVER)
        val late = (1L..10L).map { RaycastWorkBudget.Key(UUID(1, it), TickBudget.Work.POSITIONS) }
        val seen = mutableSetOf<RaycastWorkBudget.Key>()
        for (tick in 0L..10L) {
            if (budget.take(early, tick)) repeat(510) { budget.charge(tick) }
            for (key in late) {
                if (budget.take(key, tick)) {
                    seen += key
                    budget.charge(tick)
                }
            }
        }
        assertEquals(late.toSet(), seen)
    }

    @Test fun `an unfinished search keeps its place and searches started together finish one after another`() {
        val budget = RaycastWorkBudget()
        val keys = (0L..39L).map { RaycastWorkBudget.Key(UUID(4, it), TickBudget.Work.POSITIONS) }
        val turnsLeft = keys.associateWith { 5 }.toMutableMap()
        val finishedAt = HashMap<RaycastWorkBudget.Key, Long>()
        for (tick in 0L..60L) {
            for (key in keys) {
                if (key in finishedAt || !budget.acquire(key, tick)) continue
                repeat(64) { budget.charge(tick) }
                val left = turnsLeft.getValue(key) - 1
                turnsLeft[key] = left
                budget.release(key, unfinished = left > 0)
                if (left == 0) finishedAt[key] = tick
            }
        }
        assertEquals(keys.toSet(), finishedAt.keys)
        // Round-robin over 40 searches finished none before the 25th tick; in order, the first ones
        // finish within their five turns.
        assertTrue(finishedAt.values.min() <= 6, "first search finished at ${finishedAt.values.min()}")
        assertEquals(keys.map { finishedAt.getValue(it) }.sorted(), keys.map { finishedAt.getValue(it) }, "searches finished out of arrival order")
    }

    @Test fun `a search that never ends gives its place up after a bounded number of turns`() {
        val budget = RaycastWorkBudget()
        // As many as a tick grants: the late one waits until a place is given up.
        val stuck = (0L..31L).map { RaycastWorkBudget.Key(UUID(5, it), TickBudget.Work.POSITIONS) }
        val late = RaycastWorkBudget.Key(UUID(6, 0), TickBudget.Work.POSITIONS)
        var lateTurnAt = -1L
        for (tick in 0L..40L) {
            for (key in stuck) {
                if (budget.acquire(key, tick)) budget.release(key, unfinished = true)
            }
            if (tick > 0 && lateTurnAt < 0 && budget.take(late, tick)) lateTurnAt = tick
        }
        assertTrue(lateTurnAt in 2L..14L, "a late search waited behind never-ending ones until $lateTurnAt")
    }

    @Test fun `searches asked for every fourth tick, as the plan ticks them, are not held back by the reserved places`() {
        // Replaying the 10.11 forest battle with no player near (brains every fourth tick), 23 men
        // searched for posts at once: the first eight held the only places that could take a turn,
        // about two of them asked on any tick, and turns came 28-48 ticks apart (#312).
        val budget = RaycastWorkBudget()
        val keys = (0L..23L).map { RaycastWorkBudget.Key(UUID(7, it), TickBudget.Work.POSITIONS) }
        val turnsLeft = keys.associateWith { 5 }.toMutableMap()
        val finishedAt = HashMap<RaycastWorkBudget.Key, Long>()
        for (tick in 0L..200L) {
            var granted = 0
            for ((index, key) in keys.withIndex()) {
                if (key in finishedAt || (tick + index) % 4 != 0L || !budget.acquire(key, tick)) continue
                granted++
                repeat(64) { budget.charge(tick) }
                val left = turnsLeft.getValue(key) - 1
                turnsLeft[key] = left
                budget.release(key, unfinished = left > 0)
                if (left == 0) finishedAt[key] = tick
            }
            assertTrue(granted <= 32, "$granted turns in one tick")
        }
        assertEquals(keys.toSet(), finishedAt.keys)
        // Five turns asked for every fourth tick take 17 ticks unhindered; the ray limit lets about
        // seven 64-ray turns through a tick, so the six askers of a tick all go.
        assertTrue(finishedAt.values.max() <= 30, "the last of 24 searches finished at tick ${finishedAt.values.max()}")
    }
}

/** A granted turn released at once, as a finished search does. */
private fun RaycastWorkBudget.take(key: RaycastWorkBudget.Key, now: Long) = acquire(key, now).also { if (it) release(key, unfinished = false) }

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
                if (!rays.acquire(RaycastWorkBudget.Key(actor, TickBudget.Work.POSITIONS), tick)) continue
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
                if (!budget.acquire(key, tick)) continue
                turns.getValue(key) += tick
                repeat(64) { budget.charge(tick) }
                assertFalse(budget.available(tick))
                assertFalse(budget.acquire(key, tick), "One actor cannot consume another turn")
            }
        }
        assertTrue(turns.values.all { it.size >= 3 }, "Every work item must receive at least three bounded turns")
        assertTrue(turns.values.all { samples -> samples.first() <= 40 && samples.zipWithNext().all { (a, b) -> b - a <= 40L } })
    }

    @Test fun `an indivisible cover scan can overrun but cannot launch another scan that tick`() {
        val budget = RaycastWorkBudget()
        assertTrue(budget.acquire(RaycastWorkBudget.Key(UUID(1, 0), TickBudget.Work.COVER), 0))
        repeat(510) { budget.charge(0) }
        val waiting = RaycastWorkBudget.Key(UUID(1, 1), TickBudget.Work.POSITIONS)
        assertFalse(budget.acquire(waiting, 0))
        assertTrue(budget.acquire(waiting, 1))
        assertTrue(budget.available(1))
    }

    @Test fun `disappeared work expires and another world has an independent queue`() {
        val budget = RaycastWorkBudget()
        val active = RaycastWorkBudget.Key(UUID(1, 100), TickBudget.Work.POSITIONS)
        for (actor in 0L..32L) budget.acquire(RaycastWorkBudget.Key(UUID(1, actor), TickBudget.Work.COVER), 0)
        assertFalse(budget.acquire(active, 0))
        assertTrue(budget.acquire(active, 1), "Ready work within the reserved group can pass an absent actor")
        assertFalse(budget.acquire(active, 2))
        assertTrue(budget.acquire(active, 7))
        assertTrue(RaycastWorkBudget().acquire(active, 2))
    }

    @Test fun `one expensive early actor cannot repeatedly overtake waiting cheap actors`() {
        val budget = RaycastWorkBudget()
        val early = RaycastWorkBudget.Key(UUID(1, 0), TickBudget.Work.COVER)
        val late = (1L..10L).map { RaycastWorkBudget.Key(UUID(1, it), TickBudget.Work.POSITIONS) }
        val seen = mutableSetOf<RaycastWorkBudget.Key>()
        for (tick in 0L..10L) {
            if (budget.acquire(early, tick)) repeat(510) { budget.charge(tick) }
            for (key in late) {
                if (budget.acquire(key, tick)) {
                    seen += key
                    budget.charge(tick)
                }
            }
        }
        assertEquals(late.toSet(), seen)
    }
}

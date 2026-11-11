package com.sbwnpc.squad.combat.tactics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalPathBudgetTest {
    @Test fun `two busy squads receive bounded turns despite fixed entity iteration order`() {
        val budget = TacticalPathBudget()
        val members = (0..63).map { UUID(1, it.toLong()) }
        val turns = members.associateWith { mutableListOf<Long>() }
        for (tick in 0L..63L) {
            for (member in members) {
                if (budget.acquire(member, tick)) {
                    turns.getValue(member) += tick
                    assertFalse(budget.acquire(member, tick), "An actor cannot consume the next actor's turn")
                }
            }
        }
        assertTrue(turns.values.all { it.size == 4 }, "Late entities must not starve behind early entities")
        assertTrue(turns.values.all { samples -> samples.zipWithNext().all { (a, b) -> b - a == 16L } })
    }

    @Test fun `an expensive search defers later work and cancelled reservations expire`() {
        val budget = TacticalPathBudget()
        val first = UUID(1, 1)
        val abandoned = UUID(1, 2)
        val next = UUID(1, 3)
        assertTrue(budget.acquire(first, 0))
        budget.charge(0, 9_000_000)
        assertFalse(budget.acquire(abandoned, 0))
        assertFalse(budget.acquire(next, 0))
        assertTrue(budget.acquire(next, 1))
        // The absent actor's place is kept for him; what is left of the tick is anyone's (#312).
        assertTrue(budget.acquire(first, 1))
        assertTrue(budget.acquire(UUID(1, 4), 1))
        assertFalse(budget.acquire(UUID(1, 5), 1), "New work took the place kept for a waiting actor")
        assertTrue((6L..9L).all { budget.acquire(UUID(1, it), 8) }, "A disappeared requester must not hold the queue forever")
        assertTrue(TacticalPathBudget().acquire(first, 0), "A second world has an independent budget")
    }

    @Test fun `paths asked for every fourth tick are not held back by the reserved places`() {
        // With no player near a man's brain ticks every fourth tick (#312): of four reserved places
        // about one asked on any tick, and the queue gave out one path a tick of the four it may.
        val budget = TacticalPathBudget()
        val actors = (0L..23L).map { UUID(2, it) }
        val left = actors.associateWith { 3 }.toMutableMap()
        val finishedAt = HashMap<UUID, Long>()
        for (tick in 0L..120L) {
            for ((index, actor) in actors.withIndex()) {
                if (actor in finishedAt || (tick + index) % 4 != 0L || !budget.acquire(actor, tick)) continue
                left[actor] = left.getValue(actor) - 1
                if (left.getValue(actor) == 0) finishedAt[actor] = tick
            }
        }
        assertEquals(actors.toSet(), finishedAt.keys)
        // 72 paths at four a tick take 18 ticks, each man's own cadence a few more; held back, 39.
        assertTrue(finishedAt.values.max() <= 26, "the last of 24 path searches finished at tick ${finishedAt.values.max()}")
    }
}

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
        // The absent actor was reserved too; new work cannot continually overtake it.
        assertFalse(budget.acquire(first, 1))
        assertTrue(budget.acquire(first, 7), "A disappeared requester must not hold the queue forever")
        assertTrue(TacticalPathBudget().acquire(first, 0), "A second world has an independent budget")
    }
}

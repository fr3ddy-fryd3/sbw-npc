package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ResupplyTest {
    private fun men(n: Int) = (1..n).map { SupplyCandidate(UUID(0, it.toLong()), empty = false, fraction = 0.2, distance = it.toDouble()) }

    @Test fun `at most half the living squad is away at once, at least one`() {
        assertEquals(3, Resupply.pick(emptySet(), men(6), living = 6).size)
        assertEquals(2, Resupply.pick(emptySet(), men(5), living = 5).size)
        assertEquals(1, Resupply.pick(emptySet(), men(3), living = 3).size)
        // Men still on their way back hold places in the half (owner 10.11).
        assertEquals(1, Resupply.pick(emptySet(), men(6), living = 6, returning = 2).size)
        assertEquals(0, Resupply.pick(emptySet(), men(6), living = 6, returning = 3).size)
        assertEquals(1, Resupply.pick(emptySet(), men(2), living = 2).size)
        assertEquals(1, Resupply.pick(emptySet(), men(1), living = 1).size)
        // Men who need nothing still count: two of eight need rounds, both go.
        assertEquals(2, Resupply.pick(emptySet(), men(2), living = 8).size)
    }

    @Test fun `the empty go first, then the lowest, then the nearest`() {
        val low = SupplyCandidate(UUID(1, 1), empty = false, fraction = 0.05, distance = 50.0)
        val empty = SupplyCandidate(UUID(1, 2), empty = true, fraction = 0.0, distance = 90.0)
        val near = SupplyCandidate(UUID(1, 3), empty = false, fraction = 0.25, distance = 2.0)
        assertEquals(setOf(empty), Resupply.pick(emptySet(), listOf(low, empty, near), living = 2).map { c -> listOf(low, empty, near).first { it.id == c } }.toSet())
        assertEquals(setOf(empty.id, low.id), Resupply.pick(emptySet(), listOf(low, empty, near), living = 4))
    }

    @Test fun `a man keeps his trip while he still needs it, and his place is freed once he does not`() {
        val all = men(4)
        val first = Resupply.pick(emptySet(), all, living = 4)
        val emptyNow = all.last().copy(empty = true)
        assertEquals(first, Resupply.pick(first, all.dropLast(1) + emptyNow, living = 4))
        val left = Resupply.pick(first, all.filter { it.id !in first }, living = 4)
        assertEquals(all.filter { it.id !in first }.map { it.id }.toSet(), left)
    }

    @Test fun `a squadless man goes on his own, with no cap`() {
        assertEquals(1, Resupply.pick(emptySet(), men(1), living = 1, squadless = true).size)
    }
}

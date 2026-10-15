package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class OperationLossesTest {
    private fun ids(range: IntRange) = range.map { UUID(1, it.toLong()) }
    private val alpha = UUID(0, 1)
    private val bravo = UUID(0, 2)

    @Test fun `losses count over the whole operation, not per squad`() {
        val losses = OperationLosses()
        losses.observe(alpha, ids(1..5))
        losses.observe(bravo, ids(6..10))
        // Alpha is down to one man, bravo is whole: 4 of 10 lost.
        val current = mapOf(alpha to ids(1..1), bravo to ids(6..10))
        assertEquals(0.4, losses.fraction { current.getValue(it) }, 1e-9)
        assertEquals(OperationStanding.REVIEW, OperationLosses.standing(0.4))
    }

    @Test fun `thresholds review at 30 and withdraw at 60 percent`() {
        assertEquals(OperationStanding.STEADY, OperationLosses.standing(0.29))
        assertEquals(OperationStanding.REVIEW, OperationLosses.standing(0.3))
        assertEquals(OperationStanding.REVIEW, OperationLosses.standing(0.59))
        assertEquals(OperationStanding.WITHDRAW, OperationLosses.standing(0.6))
    }

    @Test fun `a reinforcement lowers the fraction and a departing squad takes its record`() {
        val losses = OperationLosses()
        losses.observe(alpha, ids(1..10))
        val current = mutableMapOf(alpha to ids(1..6))
        assertEquals(0.4, losses.fraction { current.getValue(it) }, 1e-9)
        losses.observe(bravo, ids(11..20))
        current[bravo] = ids(11..20)
        assertEquals(0.2, losses.fraction { current.getValue(it) }, 1e-9)
        losses.retain(setOf(bravo))
        assertEquals(0.0, losses.fraction { current.getValue(it) }, 1e-9)
    }

    @Test fun `a man moved between squads of the operation is not lost`() {
        val losses = OperationLosses()
        losses.observe(alpha, ids(1..4))
        losses.observe(bravo, ids(5..8))
        val current = mapOf(alpha to ids(1..3), bravo to ids(4..8))
        assertEquals(0.0, losses.fraction { current.getValue(it) }, 1e-9)
    }
}

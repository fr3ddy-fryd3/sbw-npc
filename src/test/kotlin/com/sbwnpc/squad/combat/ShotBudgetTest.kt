package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShotBudgetTest {
    @Test
    fun `normal automatic fire retains cadence and fractional time`() {
        var shots = 0
        var remaining = 0L
        repeat(200) { remaining = ShotBudget.fire(remaining + 50, 75) { shots++ } }
        assertEquals(133, shots)
        assertEquals(25L, remaining)
    }

    @Test
    fun `long stall cannot create an unbounded burst or carry old debt to next tick`() {
        var shots = 0
        val remaining = ShotBudget.fire(60_025, 100) { shots++ }
        assertEquals(8, shots)
        assertEquals(25L, remaining)
        ShotBudget.fire(remaining + 50, 100) { shots++ }
        assertEquals(8, shots)
    }

    @Test
    fun `extreme elapsed time stays bounded and invalid interval is rejected`() {
        var shots = 0
        assertEquals(0L, ShotBudget.fire(Long.MAX_VALUE, 1) { shots++ })
        assertEquals(8, shots)
        assertThrows(IllegalArgumentException::class.java) { ShotBudget.fire(100, 0) {} }
    }
}

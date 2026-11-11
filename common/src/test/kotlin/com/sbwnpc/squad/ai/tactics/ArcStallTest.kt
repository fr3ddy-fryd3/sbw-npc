package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ArcStallTest {
    private val man = UUID(0, 1)
    private val step = Math.toRadians(10.0)

    @Test fun `a man getting round the arc is not stuck however often his searches fail`() {
        val stall = ArcStall()
        for (tick in 0L..1000L step 100L) stall.note(man, 1.5 - tick / 100 * step, failedAt = tick, now = tick)
        assertFalse(stall.stuck(man, 1000L))
    }

    @Test fun `no progress for fifteen seconds with a failed search is stuck`() {
        // Forest 10.11 (#312): against a powder-snow field, 50 s searching and no step further round.
        val stall = ArcStall()
        stall.note(man, 1.0, failedAt = null, now = 0L)
        stall.note(man, 1.0, failedAt = 120L, now = 150L)
        assertFalse(stall.stuck(man, 299L))
        stall.note(man, 1.0, failedAt = 120L, now = 300L)
        assertTrue(stall.stuck(man, 300L))
    }

    @Test fun `no progress without a failed search is not stuck`() {
        // Waiting for his cover or his bound turn is no blocked arc.
        val stall = ArcStall()
        stall.note(man, 1.0, failedAt = null, now = 0L)
        stall.note(man, 1.0, failedAt = null, now = 600L)
        assertFalse(stall.stuck(man, 600L))
    }

    @Test fun `a failure from before his last step forward does not count`() {
        val stall = ArcStall()
        stall.note(man, 1.0, failedAt = 50L, now = 100L)
        stall.note(man, 0.8, failedAt = 50L, now = 200L)
        stall.note(man, 0.8, failedAt = 50L, now = 600L)
        assertFalse(stall.stuck(man, 600L))
    }

    @Test fun `walking away round a detour or wavering a few degrees is no progress`() {
        val stall = ArcStall()
        stall.note(man, 1.0, failedAt = null, now = 0L)
        stall.note(man, 1.3, failedAt = 100L, now = 100L)
        stall.note(man, 0.96, failedAt = 100L, now = 200L)
        assertTrue(stall.stuck(man, 300L))
    }

    @Test fun `leaving the arc forgets him`() {
        val stall = ArcStall()
        stall.note(man, 1.0, failedAt = 10L, now = 0L)
        stall.note(man, 1.0, failedAt = 10L, now = 400L)
        assertTrue(stall.stuck(man, 400L))
        stall.retain(emptySet())
        assertFalse(stall.stuck(man, 400L))
    }
}

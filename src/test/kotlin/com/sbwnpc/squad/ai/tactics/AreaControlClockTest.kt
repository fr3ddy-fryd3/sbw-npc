package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AreaControlClockTest {
    private fun AreaControlClock.safe(now: Long) = observe(now, true, true, true, false, false)

    @Test fun `completion requires full observed occupation interval`() {
        val clock = AreaControlClock(duration = 30)
        assertFalse(clock.safe(100))
        assertFalse(clock.safe(110))
        assertFalse(clock.safe(120))
        assertTrue(clock.safe(130))
        assertEquals(30L, clock.heldTicks)
    }

    @Test fun `missing occupation inspection chunks and observation gaps receive no credit`() {
        for (missing in 0..2) {
            val clock = AreaControlClock(duration = 30)
            clock.safe(0)
            clock.safe(10)
            clock.observe(20, missing != 0, missing != 1, missing != 2, false, false)
            assertFalse(clock.safe(1000))
            assertEquals(10L, clock.heldTicks)
            clock.safe(2000)
            assertEquals(10L, clock.heldTicks)
            assertFalse(clock.safe(2010))
            assertTrue(clock.safe(2020))
        }
    }

    @Test fun `renewed contact or incoming fire resets accumulated occupation`() {
        for (incoming in listOf(false, true)) {
            val clock = AreaControlClock(duration = 20)
            clock.safe(0)
            clock.safe(10)
            clock.observe(20, true, true, true, !incoming, incoming)
            assertEquals(0L, clock.heldTicks)
            assertFalse(clock.safe(30))
            assertFalse(clock.safe(40))
            assertTrue(clock.safe(50))
        }
    }
}

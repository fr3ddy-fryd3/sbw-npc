package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AreaControlClockTest {
    private fun AreaControlClock.safe(now: Long) = observe(now, true, true, true, false)
    private fun AreaControlClock.glanceAway(now: Long) = observe(now, true, false, true, false)

    @Test fun `completion requires full observed occupation interval`() {
        val clock = AreaControlClock(duration = 30)
        assertFalse(clock.safe(100))
        assertFalse(clock.safe(110))
        assertFalse(clock.safe(120))
        assertTrue(clock.safe(130))
        assertEquals(30L, clock.heldTicks)
    }

    @Test fun `missing occupation chunks unverified positions and observation gaps receive no credit`() {
        for (missing in 0..2) {
            val clock = AreaControlClock(duration = 30)
            clock.safe(0)
            clock.safe(10)
            clock.observe(20, missing != 1, true, missing != 0 && missing != 2, false)
            assertFalse(clock.safe(1000))
            assertEquals(10L, clock.heldTicks)
            clock.safe(2000)
            assertEquals(10L, clock.heldTicks)
            assertFalse(clock.safe(2010))
            assertTrue(clock.safe(2020))
        }
    }

    @Test fun `occupiers looking away after the sweep keep the count running`() {
        val clock = AreaControlClock(duration = 40)
        clock.safe(0)
        clock.glanceAway(10)
        clock.safe(20)
        clock.glanceAway(30)
        assertTrue(clock.glanceAway(40))
    }

    @Test fun `no credit before the point is first sighted`() {
        val clock = AreaControlClock(duration = 20)
        clock.glanceAway(0)
        clock.glanceAway(10)
        assertEquals(0L, clock.heldTicks)
        clock.safe(20)
        clock.glanceAway(30)
        assertTrue(clock.glanceAway(40))
    }

    @Test fun `an unverified position pauses the count but keeps the sweep`() {
        val clock = AreaControlClock(duration = 30)
        clock.safe(0)
        clock.safe(10)
        clock.observe(20, true, false, false, false)
        assertTrue(clock.swept)
        clock.glanceAway(30)
        clock.glanceAway(40)
        assertEquals(20L, clock.heldTicks)
    }

    @Test fun `leaving the area forgets the sweep`() {
        val clock = AreaControlClock(duration = 30)
        clock.safe(0)
        clock.safe(10)
        clock.observe(20, false, false, true, false)
        clock.glanceAway(30)
        clock.glanceAway(40)
        assertEquals(10L, clock.heldTicks)
    }

    @Test fun `renewed contact or incoming fire resets accumulated occupation and the sweep`() {
        run {
            val clock = AreaControlClock(duration = 20)
            clock.safe(0)
            clock.safe(10)
            clock.observe(20, true, true, true, true)
            assertEquals(0L, clock.heldTicks)
            clock.glanceAway(30)
            assertEquals(0L, clock.heldTicks)
            assertFalse(clock.safe(40))
            assertFalse(clock.safe(50))
            assertTrue(clock.safe(60))
        }
    }
}

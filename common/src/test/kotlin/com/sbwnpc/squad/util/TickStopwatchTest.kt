package com.sbwnpc.squad.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The firing cadence counts game ticks and keeps the part of a cooldown below one tick. */
class TickStopwatchTest {
    /** Rounds fired over [ticks] by the same loop GunAttackBehaviour runs, from an opening shot. */
    private fun rounds(cooldown: Long, ticks: Int): Int {
        val timer = TickStopwatch()
        timer.start(0)
        timer.setProgress(0, cooldown + 1)
        var fired = 0
        for (tick in 0 until ticks) {
            if (timer.progress(tick) < cooldown) continue
            var left = timer.progress(tick)
            do {
                fired++
                left -= cooldown
            } while (left - cooldown > 0)
            timer.setProgress(tick, left)
        }
        return fired
    }

    @Test fun `a cadence between whole ticks keeps its rate`() {
        // 900 rpm: 67 ms, 15 rounds a second; rounding to ticks would give 10.
        assertEquals(15.0, rounds(67, 20).toDouble(), 1.0)
    }

    @Test fun `a cadence faster than a tick fires more than one round a tick`() {
        // 1500 rpm: 40 ms, 25 rounds a second.
        assertEquals(25.0, rounds(40, 20).toDouble(), 1.0)
    }

    @Test fun `a slow cadence fires on its ticks`() {
        // A bolt rifle at 60 rpm: one opening shot, then one a second.
        assertEquals(5, rounds(1000, 81))
    }

    @Test fun `a stopped stopwatch reads zero and starts fresh`() {
        val timer = TickStopwatch()
        timer.start(10)
        timer.setProgress(10, 33)
        assertEquals(133L, timer.progress(12))
        timer.stop()
        assertEquals(0L, timer.progress(12))
        timer.start(20)
        assertEquals(50L, timer.progress(21))
    }
}

package com.sbwnpc.squad.util

/**
 * Milliseconds of game time since [start], counted in ticks of 50 ms. [progress] reads zero while
 * stopped. Setting it keeps the part below a tick ([carry]): a firing cadence that is not a whole
 * number of ticks (900 rpm, 67 ms) would otherwise round down to every second tick.
 */
class TickStopwatch {
    private var startTick = 0
    private var carry = 0L
    private var started = false

    fun start(now: Int) {
        if (!started) {
            started = true
            startTick = now
            carry = 0
        }
    }

    fun started(): Boolean = started

    fun stop() {
        started = false
    }

    fun progress(now: Int): Long = if (started) (now - startTick) * TICK_MILLIS + carry else 0

    fun setProgress(now: Int, value: Long) {
        startTick = now
        carry = value
    }

    private companion object {
        const val TICK_MILLIS = 50L
    }
}

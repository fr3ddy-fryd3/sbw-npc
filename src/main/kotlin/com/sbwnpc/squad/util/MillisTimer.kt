package com.sbwnpc.squad.util

/** Wall-clock stopwatch. [progress] reads zero while stopped; setting it moves the start time. */
class MillisTimer {
    private var startTime = 0L
    private var started = false

    fun start() {
        if (!started) {
            started = true
            startTime = System.currentTimeMillis()
        }
    }

    fun started(): Boolean = started

    fun stop() {
        started = false
    }

    var progress: Long
        get() = if (started) System.currentTimeMillis() - startTime else 0
        set(value) {
            startTime = System.currentTimeMillis() - value
        }
}

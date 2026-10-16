package com.sbwnpc.squad.ai.tactics

/** Missing inspection time is never credited as peaceful occupation. */
class AreaControlClock(private val duration: Long = 600, private val maximumObservationGap: Long = 20) {
    private var previousSafe: Long? = null
    var heldTicks = 0L
        private set
    val completed get() = heldTicks >= duration
    init {
        require(duration > 0 && maximumObservationGap > 0)
    }
    fun observe(now: Long, loaded: Boolean, occupied: Boolean, inspected: Boolean, threat: Boolean, incoming: Boolean): Boolean {
        if (threat || incoming) {
            heldTicks = 0
            previousSafe = null
        } else if (!loaded || !occupied || !inspected) {
            previousSafe = null
        } else {
            previousSafe?.let { prior ->
                require(now >= prior)
                if (now - prior <= maximumObservationGap) heldTicks += now - prior
            }
            previousSafe = now
        }
        return completed
    }
}

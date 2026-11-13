package com.sbwnpc.squad.ai.tactics

/**
 * Counts how long an occupied area has stayed quiet. Missing observation time is never credited
 * as peaceful occupation.
 *
 * Sighting the point is a one-off sweep, not a per-sample condition: men on the hill turn their
 * heads all the time, and requiring one of them to face the point on every sample let the clock
 * credit only the rare pairs of facing samples, so a held hill took minutes to count as taken.
 * The sweep is forgotten when the occupiers leave or a threat appears, so a lost area is swept
 * again.
 */
class AreaControlClock(private val duration: Long = 600, private val maximumObservationGap: Long = 20) {
    private var previousSafe: Long? = null

    /** Whether the occupiers have sighted the point since they last arrived or met a threat. */
    var swept = false
        private set
    var heldTicks = 0L
        private set
    val completed get() = heldTicks >= duration
    init {
        require(duration > 0 && maximumObservationGap > 0)
    }

    /**
     * Records one sample. [sighted] is whether an occupier sees the point this sample; [cleared]
     * is whether the area's chunks are loaded and no remembered enemy position is left unverified;
     * while false the count pauses. [threat] is a contact or incoming fire, which restarts the count.
     */
    fun observe(now: Long, occupied: Boolean, sighted: Boolean, cleared: Boolean, threat: Boolean): Boolean {
        if (threat || !occupied) {
            swept = false
        } else if (sighted && cleared) {
            swept = true
        }
        if (threat) {
            heldTicks = 0
            previousSafe = null
        } else if (!swept || !cleared) {
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

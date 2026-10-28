package com.sbwnpc.squad.entity.ai

/**
 * How long a held target may stay beyond detection range. A holding order keeps a visible target
 * past the ordinary radius, but without a limit a squad kept shooting at a man 170 blocks away
 * who was already withdrawing; everyone else lets such a target go after the same grace.
 */
internal class BeyondRangeGrace(private val graceTicks: Long) {
    private var since: Long? = null

    fun expired(inRange: Boolean, now: Long): Boolean {
        if (inRange) {
            since = null
            return false
        }
        val start = since ?: now.also { since = it }
        return now - start >= graceTicks
    }
}

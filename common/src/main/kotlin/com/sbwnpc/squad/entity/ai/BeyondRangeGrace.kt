package com.sbwnpc.squad.entity.ai

import java.util.UUID

/**
 * How long one target may stay beyond detection range. A shared contact or a holding order keeps a
 * target past the ordinary radius for as long as any squadmate refreshes it, so a squad kept
 * shooting at a man 280 blocks away; every shooter lets such a target go after the same grace.
 */
internal class BeyondRangeGrace(private val graceTicks: Long) {
    private var target: UUID? = null
    private var since = 0L

    fun expired(id: UUID?, inRange: Boolean, now: Long): Boolean {
        if (id == null || inRange) {
            target = null
            return false
        }
        if (id != target) {
            target = id
            since = now
        }
        return now - since >= graceTicks
    }
}

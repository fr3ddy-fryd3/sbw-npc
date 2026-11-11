package com.sbwnpc.squad.ai.tactics

import java.util.UUID

/**
 * Which men walking round the arc to their flank sector have stopped getting round it.
 *
 * Counting failed post searches on one anchor does not say so: the arc waypoint is worked out afresh
 * from where a man stands on every pass, so such failures seldom add up, and a squad can search for
 * posts under fire against ground it cannot cross until the attack is called off. What is measured
 * is the angle still to go round the objective. A man is stuck once it has not shrunk by
 * [PROGRESS] for [STALL_TICKS] and one of his post searches failed in that time, so a man waiting for
 * cover or his bound turn, with no failed search, is not.
 */
class ArcStall {
    private class Walk(var least: Double, var since: Long, var failedAt: Long? = null)

    private val walks = HashMap<UUID, Walk>()

    /**
     * Notes [man] on the arc at [now] with [toGo] radians still to his sector, and [failedAt], when his
     * last post search failed, if it did.
     */
    fun note(man: UUID, toGo: Double, failedAt: Long?, now: Long) {
        val walk = walks.getOrPut(man) { Walk(toGo, now) }
        if (toGo < walk.least - PROGRESS) {
            walk.least = toGo
            walk.since = now
        }
        // Only a failure since he last got further round counts: one from before he set off, or
        // before his last step forward, does not say the arc is blocked.
        if (failedAt != null && failedAt >= walk.since) walk.failedAt = failedAt
    }

    /** Whether [man] has stopped getting round the arc, as of [now]. */
    fun stuck(man: UUID, now: Long): Boolean {
        val walk = walks[man] ?: return false
        return walk.failedAt?.let { it >= walk.since } == true && now - walk.since >= STALL_TICKS
    }

    /** Forgets [man], as when he is given another sector. */
    fun forget(man: UUID) {
        walks.remove(man)
    }

    /** Forgets every man not in [onArc]: once off the arc, a man who comes back starts afresh. */
    fun retain(onArc: Set<UUID>) {
        walks.keys.retainAll(onArc)
    }

    companion object {
        /** Progress round the arc that counts, radians (5°). */
        const val PROGRESS = Math.PI / 36

        /** How long a man may get no further round before he counts as stuck (15 s). */
        const val STALL_TICKS = 300L
    }
}

package com.sbwnpc.squad.combat

import java.util.UUID

/**
 * One NPC's progress at noticing each enemy it has in sight (#114): it fills while the enemy is in
 * the cone and in view, and drains at the same rate while he is not.
 */
class SpottingProgress {
    private class Entry(var seconds: Double, var tick: Long)

    private val entries = HashMap<UUID, Entry>()

    /**
     * [target] is in view now: adds the time since it was last looked at and says whether [required]
     * seconds are reached. A target seen for the first time counts [FIRST_SIGHT_TICKS], so what takes
     * less than that is noticed at once. Null [required] (too far, too dark) never fills.
     */
    fun advance(target: UUID, now: Long, required: Double?): Boolean {
        val entry = entries.getOrPut(target) { Entry(0.0, now - FIRST_SIGHT_TICKS) }
        entry.seconds += (now - entry.tick).coerceIn(0L, MAX_STEP_TICKS) / TICKS_PER_SECOND
        entry.tick = now
        return required != null && entry.seconds >= required
    }

    /** After a scan: everyone not looked at in it drains by the time that has passed. */
    fun endScan(now: Long) {
        entries.values.removeIf { entry ->
            if (entry.tick < now) {
                entry.seconds -= (now - entry.tick).coerceAtLeast(0L) / TICKS_PER_SECOND
                entry.tick = now
            }
            entry.seconds <= 0.0
        }
    }

    /** Noticed at once, for good reason: the enemy fired, or is shooting at him. */
    fun fill(target: UUID, now: Long) {
        entries[target] = Entry(FILLED_SECONDS, now)
    }

    fun seconds(target: UUID): Double = entries[target]?.seconds ?: 0.0

    companion object {
        /** A scan finds the target about this long after it came into view: half a scan interval. */
        const val FIRST_SIGHT_TICKS = 10L

        /** What [fill] sets: it drains away in this many seconds once he is out of sight. */
        private const val FILLED_SECONDS = 60.0

        /** A long gap between looks does not count as a long look. */
        private const val MAX_STEP_TICKS = 40L

        private const val TICKS_PER_SECOND = 20.0
    }
}

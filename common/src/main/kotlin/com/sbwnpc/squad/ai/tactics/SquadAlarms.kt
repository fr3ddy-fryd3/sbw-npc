package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.floor

/**
 * What one squad has heard and not yet looked at (#254): the points its men heard a noise at, and
 * the points its scouts have already checked.
 *
 * The squad, not each man, decides whether anyone goes to look ([UnverifiedContacts.scouts] sends
 * a pair). A fight keeps firing, so every shot heard re-arms the same spot: a checked spot is
 * therefore remembered for [CHECKED_TICKS] and nothing heard within [CHECKED_RADIUS] of it sends the
 * pair back, or it would walk to and fro for as long as the shooting lasts.
 *
 * Points are kept on a [GRID] so the shots of one fight, a few blocks apart, are one spot: a spot
 * that moved with every shot rebuilt the scouts' posts each time.
 */
class SquadAlarms {
    private val heard = LinkedHashMap<Point3, Long>()
    private val checked = HashMap<Point3, Long>()

    /** A man of the squad heard a noise at [at] by [now]. */
    fun heard(at: Point3, now: Long) {
        val spot = snap(at)
        heard.remove(spot)
        heard[spot] = now
    }

    /** The scouts have seen the spot at [at] by [now]: it and what is heard round it wait out [CHECKED_TICKS]. */
    fun checked(at: Point3, now: Long) {
        checked[snap(at)] = now
        heard.keys.removeIf { near(it, at) }
    }

    /** Drops every spot heard and not yet looked at, e.g. the noise of a fight the squad itself was in. */
    fun forget() = heard.clear()

    /**
     * The spot to look at by [now]: the latest heard one not yet checked and, when [reach] is given,
     * within it of [anchor]. Null when there is none.
     */
    fun pending(now: Long, anchor: Point3, reach: Double?): Point3? {
        heard.values.removeIf { now - it > HEARD_TICKS }
        checked.values.removeIf { now - it > CHECKED_TICKS }
        return heard.keys.reversed().firstOrNull { spot ->
            checked.keys.none { near(it, spot) } && (reach == null || spot.horizontalDistanceSquared(anchor) <= reach * reach)
        }
    }

    private fun near(a: Point3, b: Point3) = a.horizontalDistanceSquared(b) <= CHECKED_RADIUS * CHECKED_RADIUS

    private fun snap(at: Point3) = Point3(cell(at.x), at.y, cell(at.z))

    private fun cell(value: Double) = floor(value / GRID) * GRID + GRID / 2

    companion object {
        /** Blocks on a side of the grid heard points are kept on. */
        const val GRID = 8.0

        /** A heard spot nobody went to look at is forgotten after this (30 s); every new noise there renews it. */
        const val HEARD_TICKS = 600L

        /** A checked spot sends nobody back for this long (60 s). */
        const val CHECKED_TICKS = 1200L

        /** Blocks round a checked spot within which a noise is the same spot. */
        const val CHECKED_RADIUS = 16.0

        /** A scout this near a spot has looked at it. */
        const val ARRIVAL = 10.0
    }
}

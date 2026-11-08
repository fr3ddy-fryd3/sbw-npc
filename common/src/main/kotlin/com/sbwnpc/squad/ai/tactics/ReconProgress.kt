package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.hypot

/** Where a recon squad is in its approach to an objective it was ordered to attack (#194). */
enum class ReconStage {
    /** Running to the staging line, [ReconProgress.STAGING_DISTANCE] short of the objective. */
    STAGING,

    /** Crouched and wary from the staging line towards an observation post. */
    STALK,

    /** At the observation post, watching the objective before it reports. */
    WATCH,

    /** It has reported what it saw, or that it saw nobody. */
    REPORTED,
}

/**
 * One recon squad's approach under one order (owner 26.10, #194): run to a staging line 90 blocks
 * short of the objective, then crouched and wary to an observation post with a view of it, watch
 * there [WATCH_TICKS] and report. Decided from the squad's distance to the objective and the time
 * spent watching; where the post is, the position search for an observer finds.
 */
class ReconProgress(val stamp: Int) {
    var stage = ReconStage.STAGING
        private set

    /** Where it falls back to and holds once reported with no infantry beside it. */
    var fallBack: Point3? = null
    private var watchFrom = 0L

    /** Advances the stage for a squad whose centre stands [distance] blocks from the objective at [now]; returns the stage. */
    fun update(distance: Double, now: Long): ReconStage {
        stage = when (stage) {
            ReconStage.STAGING -> if (distance <= STAGING_DISTANCE + ARRIVAL) ReconStage.STALK else ReconStage.STAGING
            ReconStage.STALK -> if (distance <= POST_DISTANCE) ReconStage.WATCH.also { watchFrom = now } else ReconStage.STALK
            ReconStage.WATCH -> if (now - watchFrom >= WATCH_TICKS) ReconStage.REPORTED else ReconStage.WATCH
            ReconStage.REPORTED -> ReconStage.REPORTED
        }
        return stage
    }

    /** Where the squad whose centre is at [centre] makes for in the current stage. */
    fun point(objective: Point3, centre: Point3): Point3 =
        when (stage) {
            ReconStage.STAGING -> shortOf(objective, centre, STAGING_DISTANCE)

            ReconStage.STALK -> shortOf(objective, centre, POST_DISTANCE - ARRIVAL)

            ReconStage.WATCH -> objective

            // With no infantry to join (reported with infantry, it fights and is no longer here):
            // back along its approach to 64 blocks short of the objective, and it holds there.
            ReconStage.REPORTED -> fallBack ?: objective
        }

    companion object {
        /** The staging line (owner 26.10). */
        const val STAGING_DISTANCE = 90.0

        /** Close enough to the staging line to count as on it. */
        const val ARRIVAL = 8.0

        /** The stalk ends this far from the objective, where the observer's post search takes over. */
        const val POST_DISTANCE = 72.0

        /** Watching before the report: 20 s (owner 26.10). */
        const val WATCH_TICKS = 400L

        /** Reported with no infantry to join, it holds this far from the objective (owner 26.10). */
        const val FALL_BACK_DISTANCE = 64.0

        private const val MIN_LENGTH = 1e-6

        /** The point [distance] blocks from [objective] on the line towards [from], nearer or further. */
        fun along(objective: Point3, from: Point3, distance: Double): Point3 {
            val dx = from.x - objective.x
            val dz = from.z - objective.z
            val length = hypot(dx, dz).takeIf { it >= MIN_LENGTH } ?: return from
            return Point3(objective.x + dx / length * distance, objective.y, objective.z + dz / length * distance)
        }

        /** The point [distance] blocks short of [objective] on the line towards [from]; [from] itself when nearer. */
        fun shortOf(objective: Point3, from: Point3, distance: Double): Point3 {
            val dx = from.x - objective.x
            val dz = from.z - objective.z
            val length = hypot(dx, dz)
            if (length <= distance || length < MIN_LENGTH) return from
            return Point3(objective.x + dx / length * distance, objective.y, objective.z + dz / length * distance)
        }
    }
}

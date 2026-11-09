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

    /**
     * Reported, it keeps its post and goes on reporting until contact (owner 09.11): the assault, or
     * it is discovered, ends it. Only a squad that cannot hold it falls back.
     */
    var holdsPost = false

    /** Reports sent from its final post: the report itself and each running update after it. */
    var reports = 0
        private set

    /** How far short of the objective its post lies; nearer each time it sees nobody ([closer]). */
    var postDistance = POST_DISTANCE
        private set

    /**
     * The post it watches from: where it stood on arriving. Its men observe from here; an OBSERVE on
     * the objective itself walked them to within 10 blocks of it, onto the enemy.
     */
    var post: Point3? = null
        private set

    /** When it reached its latest post, or null before the first: the operation's wait for its report counts from it. */
    var postFrom: Long? = null
        private set

    /** Advances the stage for a squad whose centre stands [distance] blocks from the objective at [now]; returns the stage. */
    fun update(distance: Double, now: Long, centre: Point3? = null): ReconStage {
        stage = when (stage) {
            ReconStage.STAGING -> if (distance <= STAGING_DISTANCE + ARRIVAL) ReconStage.STALK else ReconStage.STAGING
            ReconStage.STALK -> if (distance <= postDistance) arrive(now, centre) else ReconStage.STALK
            ReconStage.WATCH -> if (now - (postFrom ?: now) >= WATCH_TICKS) ReconStage.REPORTED else ReconStage.WATCH
            ReconStage.REPORTED -> ReconStage.REPORTED
        }
        return stage
    }

    /** Marks the report itself, the first of [REPORTS_AWAITED]. */
    fun reported(now: Long) {
        reports = 1
        nextUpdate = now + UPDATE_TICKS
    }

    /** Whether the operation has had the [REPORTS_AWAITED] reports it waits for (owner 09.11). */
    val reportsDone get() = reports >= REPORTS_AWAITED

    private fun arrive(now: Long, centre: Point3?): ReconStage {
        postFrom = now
        post = centre
        nextUpdate = now + UPDATE_TICKS
        return ReconStage.WATCH
    }

    /**
     * Seeing nobody from its post (owner 08.11), it moves [POST_STEP] blocks nearer and watches again,
     * down to [MIN_POST_DISTANCE]; false once there, and it reports what it has.
     */
    fun closer(): Boolean {
        if (stage != ReconStage.WATCH || postDistance <= MIN_POST_DISTANCE) return false
        postDistance = maxOf(MIN_POST_DISTANCE, postDistance - POST_STEP)
        stage = ReconStage.STALK
        return true
    }

    private val seen = HashSet<java.util.UUID>()
    private var nextUpdate = Long.MIN_VALUE

    /**
     * The post's running count (owner 08.11): at most every [UPDATE_TICKS] while on the post (watching,
     * or reported and [holdsPost]), how many of
     * [present] (enemies round the objective now) it had not reported before, and how many there are.
     * Null when no update is due.
     */
    fun tally(present: Set<java.util.UUID>, now: Long): Pair<Int, Int>? {
        val onPost = stage == ReconStage.WATCH || (stage == ReconStage.REPORTED && holdsPost)
        if (!onPost || now < nextUpdate) return null
        nextUpdate = now + UPDATE_TICKS
        if (stage == ReconStage.REPORTED) reports++
        val fresh = present.count { it !in seen }
        seen += present
        return fresh to present.size
    }

    /** Where the squad whose centre is at [centre] makes for in the current stage. */
    fun point(objective: Point3, centre: Point3): Point3 =
        when (stage) {
            ReconStage.STAGING -> shortOf(objective, centre, STAGING_DISTANCE)

            ReconStage.STALK -> shortOf(objective, centre, postDistance - ARRIVAL)

            ReconStage.WATCH -> post ?: centre

            // With no infantry to join (reported with infantry, it fights and is no longer here):
            // back along its approach to 64 blocks short of the objective, and it holds there.
            ReconStage.REPORTED -> if (holdsPost) post ?: centre else fallBack ?: centre
        }

    companion object {
        /** The staging line (owner 26.10). */
        const val STAGING_DISTANCE = 90.0

        /** Close enough to the staging line to count as on it. */
        const val ARRIVAL = 8.0

        /** The stalk ends this far from the objective, where the observer's post search takes over. */
        const val POST_DISTANCE = 72.0

        /** Each time it sees nobody from its post, the next one lies this much nearer (owner 08.11). */
        const val POST_STEP = 16.0

        /** It never goes nearer than this: never onto the enemy. */
        const val MIN_POST_DISTANCE = 40.0

        /** Those waiting for the recon move only after this many reports from its post (owner 09.11). */
        const val REPORTS_AWAITED = 3

        /** Watching before the report: 20 s (owner 26.10). */
        const val WATCH_TICKS = 400L

        /** Between the post's running reports: 10 s (owner 08.11). */
        const val UPDATE_TICKS = 200L

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

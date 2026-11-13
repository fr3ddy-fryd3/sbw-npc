package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.combat.ReconReach
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

    /** Reports it has sent: each running update, each "nobody seen, moving closer" and the report itself. */
    var reports = 0
        private set

    // Whether any of its reports named an enemy.
    private var foundEnemy = false

    // Whether a report found nobody new after an earlier one named an enemy.
    private var settled = false

    /**
     * One report sent from the post, naming [total] enemies, [fresh] of them not reported before
     * ([record]). It settles the picture when an earlier report named an enemy and this one finds
     * nobody new: a total that drops is an enemy gone out of view, not news.
     */
    fun noteReport(total: Int, fresh: Int) {
        if (foundEnemy && fresh == 0) settled = true
        reports++
        if (total > 0) foundEnemy = true
    }

    /** How far short of the objective its post lies; nearer each time it sees nobody ([closer]). */
    var postDistance = POST_DISTANCE
        private set

    /** The nearest post it steps in to, see [closer]. */
    var minPostDistance = MIN_POST_DISTANCE
        private set

    /** Where it gathers before the stalk: [STAGING_DISTANCE], or further out when its first post is. */
    var stagingDistance = STAGING_DISTANCE
        private set

    private var postStep = POST_STEP

    /**
     * The post distances for the light, weather and rank at the objective (#332); until set, the
     * fixed ones hold. Taken once and only while staging: a post that moved with the dusk would send
     * it back and forth. The staging line moves out with the first post, so the stalk still starts
     * short of it.
     */
    var reach: ReconReach? = null
        set(value) {
            if (field != null || value == null || stage != ReconStage.STAGING) return
            field = value
            postDistance = value.start
            minPostDistance = value.floor
            postStep = value.step
            stagingDistance = maxOf(STAGING_DISTANCE, value.start + STAGING_MARGIN)
        }

    /**
     * The post it watches from: where it stood on arriving. Its men observe from here; an OBSERVE on
     * the objective itself walked them to within 10 blocks of it, onto the enemy.
     */
    var post: Point3? = null
        private set

    /** When it reached its latest post, or null before the first: the operation's wait for its report counts from it. */
    var postFrom: Long? = null
        private set

    /**
     * The spot with a view of the objective it makes for within the current ring (owner 09.11), and the
     * ring it was searched for; null spot with [spotRing] set means no spot in that ring sees it.
     */
    var spot: Point3? = null
        private set
    var spotRing: Double? = null
        private set

    /** The search for a post with a view, made for the current ring, found [found]. */
    fun searched(found: Point3?, now: Long) {
        spot = found
        spotRing = postDistance
        spotSince = now
    }

    private var spotSince = 0L

    /**
     * Whether its operation no longer waits for its report: then it does not back off out of its
     * floor, there is no point once the infantry goes in. Set by the caller before [update].
     */
    var released = false

    /**
     * Whether it is backing off: stalking from inside [minPostDistance] (owner 13.11, #332), where the
     * enemy notices a scout, it makes back out to its floor before it posts, whatever it sees on the way.
     */
    var backingOff = false
        private set

    /**
     * Advances the stage for a squad whose centre stands [distance] blocks from the objective at [now];
     * returns the stage. In the ring it takes its post only where it [sees] the objective: over broken
     * ground the ring alone let it settle blind, or walk on almost onto the enemy. Once [enemySeen], it
     * posts at once.
     */
    fun update(distance: Double, now: Long, centre: Point3? = null, sees: Boolean = true, enemySeen: Boolean = false): ReconStage {
        stage = when (stage) {
            ReconStage.STAGING -> if (distance <= stagingDistance + ARRIVAL) ReconStage.STALK else ReconStage.STAGING
            ReconStage.STALK -> stalk(distance, now, centre, sees, enemySeen)
            ReconStage.WATCH -> if (now - (postFrom ?: now) >= WATCH_TICKS) ReconStage.REPORTED else ReconStage.WATCH
            ReconStage.REPORTED -> ReconStage.REPORTED
        }
        return stage
    }

    /** The stalk towards its post, see [update]. */
    private fun stalk(distance: Double, now: Long, centre: Point3?, sees: Boolean, enemySeen: Boolean): ReconStage {
        if (!released && distance < minPostDistance - ARRIVAL) backingOff = true
        // Out to the floor itself, not just past the threshold, so it does not flip at the edge.
        if (backingOff && (released || distance >= minPostDistance)) backingOff = false
        // A spot it cannot get to in time is given up: it posts at the ring as before.
        if (spot != null && now - spotSince > SPOT_TICKS) spot = null
        // Seeing the enemy already, it has its view: it posts where it stands (owner 09.11, a
        // sentry 63 blocks out kept every spot nearer the ring out of bounds and it stood blind).
        // At its spot it has arrived, wherever the ring falls: the spot lies on the ring itself, and
        // the men round it put their centre a block or two outside, so it waited out SPOT_TICKS there
        // (playtest 12.11).
        val inView = arrived(distance, postDistance, spot?.takeIf { spotRing == postDistance }, centre) && (sees || spot == null)
        return if (!backingOff && (enemySeen || inView)) arrive(now, centre) else ReconStage.STALK
    }

    /** The report itself was sent at [now]: the next running update is a full interval later. */
    fun reported(now: Long) {
        nextUpdate = now + UPDATE_TICKS
    }

    /**
     * Whether the operation may go in (owner 10.11): once a report finds no enemy the earlier ones had
     * not named, or after [REPORTS_AWAITED] reports. The first sighting alone sent the infantry in
     * before the recon had a picture of the enemy.
     */
    val reportsDone get() = settled || reports >= REPORTS_AWAITED

    private fun arrive(now: Long, centre: Point3?): ReconStage {
        postFrom = now
        post = centre
        nextUpdate = now + UPDATE_TICKS
        return ReconStage.WATCH
    }

    /**
     * Seeing nobody from its post (owner 08.11), it moves a step nearer ([POST_STEP] until its [reach] is set) and
     * watches again, down to [minPostDistance]; false once there, and it reports what it has.
     */
    fun closer(): Boolean {
        if (stage != ReconStage.WATCH || postDistance <= minPostDistance) return false
        postDistance = maxOf(minPostDistance, postDistance - postStep)
        stage = ReconStage.STALK
        // Moving nearer is a report towards [REPORTS_AWAITED], never one that settles the picture.
        reports++
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
        return record(present) to present.size
    }

    /** Adds [present] to the enemies it has reported; returns how many of them it had not. */
    fun record(present: Set<java.util.UUID>): Int {
        val fresh = present.count { it !in seen }
        seen += present
        return fresh
    }

    /** Where the squad whose centre is at [centre] makes for in the current stage. */
    fun point(objective: Point3, centre: Point3): Point3 =
        when (stage) {
            ReconStage.STAGING -> shortOf(objective, centre, stagingDistance)

            ReconStage.STALK ->
                if (backingOff) {
                    along(objective, centre, (minPostDistance + postDistance) / 2)
                } else {
                    // Never inside the floor: at the floor the ring's arrival margin would aim just past it.
                    spot?.takeIf { spotRing == postDistance } ?: shortOf(objective, centre, maxOf(minPostDistance, postDistance - ARRIVAL))
                }

            ReconStage.WATCH -> post ?: centre

            // With no infantry to join (reported with infantry, it fights and is no longer here):
            // back along its approach to 64 blocks short of the objective, and it holds there.
            ReconStage.REPORTED -> if (holdsPost) post ?: centre else fallBack ?: centre
        }

    companion object {
        /** The staging line (owner 26.10). */
        const val STAGING_DISTANCE = 90.0

        /** A fitted staging line lies this far beyond the first post (#332). */
        const val STAGING_MARGIN = 16.0

        /** Close enough to the staging line to count as on it. */
        const val ARRIVAL = 8.0

        /** The stalk ends this far from the objective, where the observer's post search takes over, until its [reach] is set. */
        const val POST_DISTANCE = 72.0

        /** Each time it sees nobody from its post, the next one lies this much nearer (owner 08.11). */
        const val POST_STEP = 16.0

        /** It never goes nearer than this: never onto the enemy; until its [reach] is set. */
        const val MIN_POST_DISTANCE = 40.0

        /** Seeing nobody, those waiting for the recon move after this many of its reports (owner 09.11). */
        const val REPORTS_AWAITED = 3

        /** How long it makes for a spot with a view before it gives it up (60 s). */
        const val SPOT_TICKS = 1200L

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

/** Inside the [ring], or within [ReconProgress.ARRIVAL] of the [spot] it searched there. */
private fun arrived(distance: Double, ring: Double, spot: Point3?, centre: Point3?): Boolean = distance <= ring || (spot != null && centre != null && kotlin.math.hypot(centre.x - spot.x, centre.z - spot.z) <= ReconProgress.ARRIVAL)

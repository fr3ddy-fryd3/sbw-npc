package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.WeaponReach
import com.sbwnpc.squad.combat.tactics.DefensiveOverwatch
import com.sbwnpc.squad.combat.tactics.PositionPaths
import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * One man's search for a firing spot near him that keeps a clear shot on his target while hiding
 * as much of his body as the terrain allows: a small grid first, a wider one when he has no shot
 * where he stands.
 *
 * The search is spread over ticks by [TickBudget] and [TacticalBudget]: it keeps its place in the
 * grid between calls ([deferred]), so a search cut short resumes instead of starting over.
 */
class FiringSpotSearch(private val navigator: CombatNavigator) {
    private var pending: Pending? = null

    /** The last [find] ran out of budget before it finished: call again soon, not after a hold. */
    var deferred = false
        private set

    /** Drops the search in progress: the man moved, or his target or weapon changed. */
    fun clear() {
        pending = null
    }

    /**
     * The best spot within reach that beats where he stands, or null: none does, or the search is
     * [deferred]. Its path, when one was searched, is handed to the navigator.
     */
    fun find(shot: Shot): Vec3? {
        deferred = true
        return TickBudget.search<Vec3?>(shot.entity, TickBudget.Work.LOCAL_FIRE, null) {
            deferred = false
            best(shot)
        }
    }

    /** A man, his target, his level and where he may shift: what every score of a spot reads. */
    class Shot(val level: ServerLevel, val entity: NpcEntity, val target: LivingEntity, val bounds: RelocationBounds) {
        val eyeHeight = entity.eyeHeight.toDouble()
    }

    /**
     * Null if [pos] has no clear shot at [target] at all, or lies outside [bounds] or the enemy
     * clearance: useless as a firing spot. Otherwise the concealment score: the highest of
     * [CONCEALMENT_HEIGHTS] at which a raycast from the target's eye to a point that high above
     * [pos] is BLOCKED (real terrain in the way), or `0.0` if none are blocked (open ground, still
     * a valid spot, just no concealment bonus). Every height tested is below [eyeHeight], so a high
     * score can never itself block the line-of-fire check: a candidate "blocked at 1.5" still
     * sees and shoots from its actual eye height, just with everything below the neck hidden.
     */
    private fun concealment(shot: Shot, pos: Vec3, vehicleHulls: List<AABB>): Double? {
        val level = shot.level
        val entity = shot.entity
        val target = shot.target
        if (!shot.bounds.permits(pos) || !navigator.safePosition(entity, target, pos)) return null
        val myEye = Vec3(pos.x, pos.y + shot.eyeHeight, pos.z)
        val outsideWeaponRange = !WeaponReach.inRange(entity, myEye, target.eyePosition)
        if (!outsideWeaponRange && Sightline.blocked(level, target.eyePosition, myEye, entity)) return null
        // Standing behind armour is cover from the enemy, but it is not a firing position.
        if (!outsideWeaponRange && Sightline.crosses(vehicleHulls, myEye, target.eyePosition)) return null
        if (!outsideWeaponRange && Sightline.ownHullInLane(entity, myEye, target.eyePosition, entity.spread, target)) return null
        // Nor is standing ON one. Vehicles have collision, so a mob that walks into a hull rides up
        // onto it, and a spot inside the box is a spot on the roof of an APC.
        if (vehicleHulls.any { it.contains(Vec3(pos.x, pos.y + ON_HULL_PROBE, pos.z)) }) return null
        if (entity.usingSidearm && !DefensiveOverwatch.nearbyCover(pos, target.eyePosition) { from, to -> Sightline.blocked(level, from, to, entity) }) return 0.0
        for (height in CONCEALMENT_HEIGHTS) {
            val point = Vec3(pos.x, pos.y + height, pos.z)
            if (Sightline.blocked(level, target.eyePosition, point, entity)) return height
        }
        return 0.0
    }

    /** [concealment] at [pos], with the hulls on the line from it to the target. */
    fun concealmentAt(shot: Shot, pos: Vec3): Double? = concealment(shot, pos, Sightline.vehicleHulls(shot.level, AABB(pos, shot.target.position()).inflate(VEHICLE_LANE_MARGIN), shot.entity, shot.target))

    /**
     * Player-facing visual, per user request — same idiom as SeekCoverBehaviour's own
     * markCoverChoice. One burst each time a spot is (re-)picked, whether or not it's actually
     * different from the last one, so it's visible even for a squad member that keeps re-confirming
     * its own current position as still the best available. Gated on [DebugFlags.markersEnabled] —
     * one place to toggle these off, since the user expects to do that fairly often (e.g. playing
     * with a friend).
     */
    fun mark(level: ServerLevel, pos: Vec3) {
        if (!DebugFlags.markersEnabled) return
        level.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(FIRING_POSITION_COLOR, MARKER_SIZE),
            pos.x, pos.y + MARKER_LIFT, pos.z, MARKER_COUNT, MARKER_SPREAD, MARKER_SPREAD, MARKER_SPREAD, 0.0,
        )
    }

    /**
     * Scans a small grid within [POSITION_SEARCH_RADIUS] of his position for the best-scoring valid
     * firing spot (see [concealment]) — null if nothing beats his own current position (including
     * if the current position IS the best, the common case once already settled somewhere decent),
     * so the caller knows to just stay put.
     */
    private fun best(shot: Shot): Vec3? {
        val grid = Grid(shot)
        val origin = grid.origin
        // Standing in a grenade's blast is never the best spot, however good the view.
        val here = if (GrenadeHazard.threatens(shot.level, origin)) null else concealment(shot, origin, grid.hulls)
        // Shoulder to shoulder with a squadmate is not a spot worth keeping: any free spot in
        // reach beats it. Still kept if nothing else will do — that's the null return below.
        val baseline = if (here == null || FiringSpots.crowded(origin, grid.taken)) -1.0 else here

        // Resume the wider search directly; restarting the small grid would discard its queue.
        val resumed = pending
        if (resumed != null && resumed.matches(shot.entity, shot.target) && resumed.radius == STUCK_SEARCH_RADIUS) {
            return sweep(grid, STUCK_SEARCH_RADIUS, STUCK_GRID_STEP, baseline)
        }

        sweep(grid, POSITION_SEARCH_RADIUS, POSITION_GRID_STEP, baseline)?.let { return it }
        if (deferred) return null
        // With no reachable improvement nearby, try the wider grid for a blocked or exposed post.
        // Open ground remains valid when the wider search cannot find reachable shelter.
        if (here == null || here == 0.0) return sweep(grid, STUCK_SEARCH_RADIUS, STUCK_GRID_STEP, baseline)
        return null
    }

    /** What one search tests every candidate against, fetched once per [best]. */
    private class Grid(val shot: Shot) {
        val origin: Vec3 = shot.entity.position()

        // Fetched once for the whole grid: the candidates are all tested against the same handful
        // of hulls, and an entity query per candidate would be far more than this search can afford.
        val hulls = Sightline.vehicleHulls(shot.level, AABB(origin, shot.target.position()).inflate(STUCK_SEARCH_RADIUS + VEHICLE_LANE_MARGIN), shot.entity, shot.target)

        // Walked once for the whole search rather than per candidate — see FiringSpots.nearby.
        val taken = FiringSpots.nearbyWithBodies(shot.level, shot.entity, STUCK_SEARCH_RADIUS)

        /** Whether [point] is free of other men's spots and out of any grenade's blast: the cheap tests, made first. */
        fun free(point: Vec3): Boolean = !FiringSpots.crowded(point, taken) && !GrenadeHazard.threatens(shot.level, point)

        /** Whether his line of fire from his eye at [point] passes his allies. */
        fun laneClear(point: Vec3) = FriendlyFireGuard.assess(shot.entity, shot.target.eyePosition, shot.entity.spread, shot.target.position(), 0.0, point.add(0.0, shot.eyeHeight, 0.0)).lineClear
    }

    private data class Candidate(val point: Vec3, val concealment: Double)

    /** A grid search in progress: its probes, how far it got, and the candidates it kept. */
    private class Pending(
        val origin: Vec3,
        val target: java.util.UUID,
        val aim: Vec3,
        val radius: Double,
        step: Int,
    ) {
        val probes = (-radius.toInt()..radius.toInt() step step).flatMap { dx ->
            (-radius.toInt()..radius.toInt() step step).filter { dz -> dx * dx + dz * dz <= radius * radius }.map { dz -> origin.add(dx.toDouble(), 0.0, dz.toDouble()) }
        }
        var probeIndex = 0
        var sorted = false
        val remaining = ArrayList<Candidate>()

        /** He has not moved off the search's origin, and his target is the same and where it was. */
        fun matches(entity: NpcEntity, enemy: LivingEntity): Boolean = origin.distanceToSqr(entity.position()) <= RESUME_DRIFT * RESUME_DRIFT && target == enemy.uuid && aim.distanceToSqr(enemy.eyePosition) <= RESUME_AIM_DRIFT * RESUME_AIM_DRIFT
    }

    private fun sweep(grid: Grid, radius: Double, step: Int, startingScore: Double): Vec3? {
        val shot = grid.shot
        val resumed = pending?.takeIf { it.radius == radius && it.matches(shot.entity, shot.target) }
        val search = resumed ?: Pending(grid.origin, shot.target.uuid, shot.target.eyePosition, radius, step).also { pending = it }
        if (!collect(search, grid, startingScore)) {
            deferred = true
            return null
        }
        if (search.remaining.isEmpty()) {
            pending = null
            return null
        }
        if (!TacticalBudget.path(shot.entity)) {
            deferred = true
            return null
        }
        return pathToBest(search, grid, startingScore)
    }

    /** Paths to the best tier of [search]'s candidates that still qualify; the spot reached, or null to go on later. */
    private fun pathToBest(search: Pending, grid: Grid, startingScore: Double): Vec3? {
        val shot = grid.shot
        val tier = search.remaining.first().concealment
        val batch = search.remaining.takeWhile { it.concealment == tier }.take(PATH_BATCH)
        val points = batch.filter { grid.free(it.point) && beats(grid, it.point, startingScore) && grid.laneClear(it.point) }.map { it.point }
        // Rejected geometry cannot become the first candidate again while waiting for A*.
        search.remaining.removeAll { it in batch && it.point !in points }
        val path = if (points.isEmpty()) null else TacticalPositions.pathToCandidates(shot.entity, points)
        val chosen = path?.takeIf { it.canReach() }?.let { route -> points.firstOrNull { PositionPaths.walkingNode(it) == route.target } }
        if (chosen != null && navigator.safePath(shot.entity, shot.target, path!!)) {
            navigator.select(path)
            pending = null
            return chosen
        }
        search.remaining.removeAll { if (chosen == null) it in batch else it.point == chosen }
        deferred = search.remaining.isNotEmpty()
        if (!deferred) pending = null
        return null
    }

    private fun beats(grid: Grid, point: Vec3, score: Double) = concealment(grid.shot, point, grid.hulls)?.let { it > score } == true

    /** Scores [search]'s probes into candidates, [PROBES_PER_CALL] at a time; false while probes are left. */
    private fun collect(search: Pending, grid: Grid, startingScore: Double): Boolean {
        if (search.sorted) return true
        val level = grid.shot.level
        var inspected = 0
        while (search.probeIndex < search.probes.size) {
            if (!TickBudget.hasRaycasts(level) || inspected++ >= PROBES_PER_CALL) return false
            val probe = search.probes[search.probeIndex++]
            val ground = Terrain.standableOrNull(level, probe.x, probe.y, probe.z) ?: continue
            if (!grid.free(ground)) continue
            val score = concealment(grid.shot, ground, grid.hulls)?.takeIf { it > startingScore } ?: continue
            if (grid.laneClear(ground)) search.remaining.add(Candidate(ground, score))
        }
        // A good-looking unreachable post must not hide the next reachable sheltered candidate.
        search.remaining.sortWith(compareByDescending<Candidate> { it.concealment }.thenBy { it.point.distanceToSqr(search.origin) })
        search.sorted = true
        return true
    }

    companion object {
        /** Slack around the shooter-to-target line when looking for vehicles on it. */
        const val VEHICLE_LANE_MARGIN = 2.0

        /**
         * Used only when there is no shot at all from where the mob stands — a vehicle hull is
         * longer than the ordinary search is wide. Also the reach in which other men's spots count
         * as taken.
         */
        const val STUCK_SEARCH_RADIUS = 14.0

        // How far out to look for a better partial-cover firing spot once already in range — a
        // local shuffle, not a retreat search (contrast SeekCoverBehaviour's much larger MIN/MAX_
        // RADIUS, which is for fleeing to real cover far from a threat). Widened from 4.0 to 7.0 per
        // user request; 0 (staying exactly put) is still always in range via the baseline score
        // for his current position, so this only affects how far it's willing to reposition,
        // never whether it's allowed to just hold where it already is.
        private const val POSITION_SEARCH_RADIUS = 7.0
        private const val STUCK_GRID_STEP = 3
        private const val POSITION_GRID_STEP = 2

        /** Grid probes one call inspects before it yields the tick. */
        private const val PROBES_PER_CALL = 24

        /** Candidates of one concealment tier one path search tries at once. */
        private const val PATH_BATCH = 16

        /** A search resumes while he stands within this of its origin... */
        private const val RESUME_DRIFT = 4.0

        /** ...and his target's eye is within this of where it was. */
        private const val RESUME_AIM_DRIFT = 2.0

        /** Height above a spot tested for being inside a hull: a spot there is on its roof. */
        private const val ON_HULL_PROBE = 0.5
        private const val MARKER_SIZE = 1.5f
        private const val MARKER_LIFT = 0.5
        private const val MARKER_COUNT = 12
        private const val MARKER_SPREAD = 0.3

        // Priority order for the concealment score — highest first (a candidate blocked at 1.5
        // hides more of the mob's body than one only blocked at 0.5). All sit below
        // NpcEntity.eyeHeight (~1.62 for this model's standard proportions), so a high score never
        // conflicts with the separate line-of-fire requirement (see concealment's doc comment).
        private val CONCEALMENT_HEIGHTS = listOf(1.5, 1.0, 0.5)

        // Sky blue — debug visual for the chosen spot, see mark().
        private val FIRING_POSITION_COLOR = org.joml.Vector3f(0.3f, 0.6f, 1.0f)
    }
}

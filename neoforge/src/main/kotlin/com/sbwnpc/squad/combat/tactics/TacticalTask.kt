package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3

class TacticalTask(val job: TacticalJob, val anchor: Vec3, var focus: Vec3?, val plan: Int) {
    var position: Vec3? = null
    internal var usesObservedAim = false
    internal var observedAim: TacticalAim? = null
    var nextSearch = Long.MIN_VALUE
    var failures = 0
    var lastProgress = 0L
    var closest = Double.MAX_VALUE
    var search: TacticalPositionSearch? = null
    var nextValidation = Long.MIN_VALUE

    /** The walked route to [anchor] a transit leg follows, see [TacticalRoutes.routeLeg]. */
    var route: List<Vec3>? = null
    var nextRoutePlan = Long.MIN_VALUE

    /** An expanded fire search keeps the man within reach of the post it started from. */
    fun permitsFront(point: Vec3): Boolean = !expandedFireSearch || TacticalFirePosts.withinPost(localFirePost!!, point, recovery = true)
    fun deployingFront(point: Vec3): Boolean = !permitsFront(point)

    /** Retains assignment/sector while the individual shooter searches a bounded local post. */
    var localFirePost: Vec3? = null
    val localFireRecovery get() = localFirePost != null
    var expandedFireSearch = false
        internal set

    var pausedAt: Long? = null
        private set
    private var pauseReason: String? = null
    internal var navigationPath: net.minecraft.world.level.pathfinder.Path? = null

    internal fun suspend(now: Long, reason: String): Boolean {
        if (pauseReason == reason) return false
        if (pausedAt == null) pausedAt = now
        pauseReason = reason
        return true
    }

    internal fun resume(now: Long): Boolean {
        val since = pausedAt ?: return false
        lastProgress += now - since
        nextSearch = now
        pausedAt = null
        pauseReason = null
        return true
    }

    /** Identity protects a newer path started by cover, medical or another behavior. */
    internal fun releaseNavigation(current: net.minecraft.world.level.pathfinder.Path?, stop: () -> Unit) {
        if (navigationPath != null && navigationPath === current) stop()
        navigationPath = null
    }
}

/** Keeps progress across tick budgets, including candidates after an unreachable first choice. */
class TacticalPositionSearch(val origin: Vec3, probes: List<Vec3>, val aim: TacticalAim? = null) {
    var probes = probes
        private set
    private val probedCells = probes.mapTo(HashSet()) { net.minecraft.core.BlockPos.containing(it) }

    fun refineOverwatch(anchor: Vec3, floor: Vec3) {
        probes = probes + DefensiveOverwatch.refinements(anchor, floor).filter { probedCells.add(net.minecraft.core.BlockPos.containing(it)) }
    }
    var denseRecovery = false
        private set
    var probeIndex = 0
    val scored = LinkedHashMap<Vec3, TacticalPositionScore>()
    val visited = HashSet<Vec3>()
    var destinations: List<Vec3>? = null
    var destinationIndex = 0
    internal var routeValidation: TacticalRouteValidation? = null
    internal var pendingPath: List<Vec3>? = null
    internal val rejectedRoutes = HashSet<Vec3>()
    internal var pathAttempts = 0

    internal fun skipRejectedRoutes() {
        val ordered = destinations ?: return
        while (destinationIndex < ordered.size && ordered[destinationIndex] in rejectedRoutes) destinationIndex++
    }
    val rejections = LinkedHashMap<String, Int>()

    /** Exhaust the cheap rings first, then inspect block-sized gaps within the same radius. */
    fun extendRecovery(post: Vec3): Boolean {
        if (denseRecovery) return false
        denseRecovery = true
        probes = probes + TacticalFirePosts.denseProbes(post)
        clearCandidates()
        return true
    }

    fun clearCandidates() {
        routeValidation = null
        pendingPath = null
        scored.clear()
        destinations = null
        destinationIndex = 0
    }

    /** Use a real nearby covered candidate before a moving target invalidates a long scan.
     * Height searches still finish their full scan before ranking the highest protected floor. */
    internal fun readyBatch(overwatch: Boolean): Boolean = !overwatch && scored.values.any { !it.open }

    fun needsRestart(position: Vec3, currentAim: TacticalAim?): Boolean {
        if (origin.distanceTo(position) > 4.0 || aim?.target != currentAim?.target) return true
        if (aim == null || currentAim == null) return aim != currentAim
        return aim.eye.distanceTo(currentAim.eye) > 2.0
    }

    fun reject(reason: String) {
        rejections[reason] = (rejections[reason] ?: 0) + 1
    }
}

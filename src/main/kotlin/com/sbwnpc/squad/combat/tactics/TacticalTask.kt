package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3

class TacticalTask(val job: TacticalJob, val anchor: Vec3, var focus: Vec3?, val plan: Int) {
    var position: Vec3? = null
    var firingTarget: java.util.UUID? = null
    var nextSearch = Long.MIN_VALUE
    var failures = 0
    var lastProgress = 0L
    var closest = Double.MAX_VALUE
    var search: TacticalPositionSearch? = null
    var nextValidation = Long.MIN_VALUE
    var staging: Vec3? = null
    var frontage: TacticalFrontPost? = null
    fun permitsFront(point: Vec3): Boolean =
        if (expandedFireSearch) {
            TacticalFirePosts.withinPost(localFirePost!!, point, recovery = true)
        } else {
            frontage?.permits(point, localFireRecovery) != false
        }
    fun deployingFront(point: Vec3): Boolean = !permitsFront(point)
    var opensLane = false
    var probeAdvance = 4.0
    var maneuverPost = false

    /** Retains assignment/sector while the individual shooter searches a bounded local post. */
    var localFirePost: Vec3? = null
    val localFireRecovery get() = localFirePost != null
    var expandedFireSearch = false
        internal set
    var pausedAt: Long? = null
        private set
    private var pauseReason: String? = null
    internal var navigationPath: net.minecraft.world.level.pathfinder.Path? = null
    private var movementBlocker: TacticalMovementBlocker? = null

    /** A support-role exchange keeps the physical post and owned route of each actor. */
    internal fun withJob(next: TacticalJob, anchor: Vec3): TacticalTask =
        TacticalTask(next, anchor, focus, plan).also {
            it.position = position
            it.firingTarget = firingTarget
            it.nextSearch = nextSearch
            it.nextValidation = nextValidation
            it.lastProgress = lastProgress
            it.closest = closest
            it.frontage = frontage
            it.staging = if (next == TacticalJob.WAIT) staging ?: position ?: this.anchor else null
            it.localFirePost = localFirePost
            it.expandedFireSearch = expandedFireSearch
            it.navigationPath = navigationPath
            navigationPath = null
        }

    /** Observations explain individual preemption without changing timers or navigation. */
    internal fun observeBlocker(member: java.util.UUID, now: Long, blocker: TacticalMovementBlocker?, events: TacticalEventSink) {
        if (movementBlocker == blocker) return
        val previous = movementBlocker
        movementBlocker = blocker
        events.emit(TacticalEvent.MovementBlocked(plan, now, member, previous, blocker))
    }

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
class TacticalPositionSearch(val origin: Vec3, probes: List<Vec3>, val aim: TacticalAim? = null, val deployment: Boolean = false) {
    var probes = probes
        private set
    var denseRecovery = false
        private set
    var probeIndex = 0
    val scored = LinkedHashMap<Vec3, TacticalPositionScore>()
    val visited = HashSet<Vec3>()
    var destinations: List<Vec3>? = null
    var destinationIndex = 0
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
        scored.clear()
        destinations = null
        destinationIndex = 0
    }

    fun needsRestart(position: Vec3, currentAim: TacticalAim?, deployment: Boolean = this.deployment): Boolean {
        if (this.deployment != deployment) return true
        if (origin.distanceTo(position) > 4.0 || aim?.target != currentAim?.target) return true
        if (aim == null || currentAim == null) return aim != currentAim
        return aim.eye.distanceTo(currentAim.eye) > 2.0
    }

    fun reject(reason: String) {
        rejections[reason] = (rejections[reason] ?: 0) + 1
    }
}

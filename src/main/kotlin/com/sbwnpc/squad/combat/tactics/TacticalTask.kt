package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3

class TacticalTask(val job: TacticalJob, val anchor: Vec3, var focus: Vec3?, val plan: Int) {
    var position: Vec3? = null
    var nextSearch = Long.MIN_VALUE
    var failures = 0
    var lastProgress = 0L
    var closest = Double.MAX_VALUE
    var search: TacticalPositionSearch? = null
    var nextValidation = Long.MIN_VALUE
    var staging: Vec3? = null
    var opensLane = false
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
class TacticalPositionSearch(val origin: Vec3, val probes: List<Vec3>) {
    var probeIndex = 0
    val scored = LinkedHashMap<Vec3, Double>()
    val visited = HashSet<Vec3>()
    var destinations: List<Vec3>? = null
    var destinationIndex = 0
    val rejections = LinkedHashMap<String,Int>()
    fun reject(reason: String) { rejections[reason]=(rejections[reason] ?: 0)+1 }
}

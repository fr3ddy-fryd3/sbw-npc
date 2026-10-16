package com.sbwnpc.squad.combat.tactics.operation

import net.minecraft.world.phys.Vec3

/** One blocked route episode. Probes resume between native admissions and eventually back off. */
internal class AttackRouteRetry {
    var target: Vec3? = null
        private set
    var rounds = 0
        private set
    var nextAt = Long.MAX_VALUE
        private set
    private var probe = 0

    fun blocked(goal: Vec3, now: Long) {
        target = goal
        probe = 0
        nextAt = if (rounds < MAX_ROUNDS) now + BACKOFF * (1L shl rounds) else Long.MAX_VALUE
    }

    fun candidate(from: Vec3, now: Long): Vec3? {
        val goal = target ?: return null
        if (now < nextAt || rounds >= MAX_ROUNDS) return null
        val delta = goal.subtract(from).multiply(1.0, 0.0, 1.0)
        val forward = if (delta.lengthSqr() < 0.01) Vec3(1.0, 0.0, 0.0) else delta.normalize()
        val side = Vec3(-forward.z, 0.0, forward.x)
        val distance = minOf(listOf(16.0, 24.0, 32.0)[probe / LATERALS.size], delta.length())
        return from.add(forward.scale(distance)).add(side.scale(LATERALS[probe % LATERALS.size]))
    }

    fun rejected(now: Long) {
        probe++
        if (probe < PROBES) return
        rounds++
        probe = 0
        nextAt = if (rounds < MAX_ROUNDS) now + BACKOFF * (1L shl rounds) else Long.MAX_VALUE
    }

    fun accepted() {
        rounds++
        probe = 0
        nextAt = Long.MAX_VALUE
    }

    fun settled() {
        target = null
        rounds = 0
        probe = 0
        nextAt = Long.MAX_VALUE
    }

    companion object {
        private val LATERALS = listOf(0.0, -16.0, 16.0, -32.0, 32.0)
        private const val PROBES = 15
        private const val MAX_ROUNDS = 3
        private const val BACKOFF = 200L
    }
}

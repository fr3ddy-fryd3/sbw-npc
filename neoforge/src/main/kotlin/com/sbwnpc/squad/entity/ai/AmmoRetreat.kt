package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One empty-ammunition episode; losing and reacquiring a target does not renew its allowance. */
class AmmoRetreat {
    var origin: Vec3? = null
        private set
    private var started = 0L
    private var squad: UUID? = null
    private var stamp: Int? = null
    var holding = false
        private set

    fun begin(position: Vec3, now: Long, squad: UUID?, stamp: Int?): Boolean {
        if (origin != null && this.squad == squad && this.stamp == stamp) return false
        origin = position
        started = now
        this.squad = squad
        this.stamp = stamp
        holding = false
        return true
    }

    fun permits(point: Vec3): Boolean =
        origin?.let {
            point.subtract(it).horizontalDistance() <= RADIUS && kotlin.math.abs(point.y - it.y) <= 12.0
        } == true

    fun exhausted(position: Vec3, now: Long): Boolean = holding || now - started >= MAX_TICKS || origin?.let { position.subtract(it).horizontalDistance() >= RADIUS - 2.0 } == true

    fun hold() {
        holding = true
    }
    fun reset() {
        origin = null
        holding = false
    }

    companion object {
        const val RADIUS = 32.0
        const val MAX_TICKS = 200L
    }
}

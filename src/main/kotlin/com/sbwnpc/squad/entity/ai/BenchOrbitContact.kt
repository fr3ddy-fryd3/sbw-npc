package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3

/** Confirmed contact drives the orbit; an unseen target cannot drag the centre through walls. */
class BenchOrbitContact {
    var center: Vec3? = null
        private set
    var observedAt: Long? = null
        private set
    private var observed: Vec3? = null
    private var stamp: Int? = null

    fun resolve(now: Long, orderStamp: Int, ordered: Vec3, observation: Vec3?): Vec3 {
        if (stamp != orderStamp) {
            stamp = orderStamp
            center = observation ?: ordered
            observed = null
            observedAt = null
        }
        if (observation != null) {
            observed = observation
            observedAt = now
        }
        val desired = observed?.takeIf { now - (observedAt ?: now) <= HOLD_TICKS } ?: ordered
        val current = center ?: desired
        // Bound displacement per tick; changing the observed member of a group cannot teleport
        // the ring under the aircraft or flip its tangent every sensor scan.
        val delta = desired.subtract(current)
        val horizontal = delta.horizontalDistance()
        val factor = if (horizontal <= MAX_STEP) 1.0 else MAX_STEP / horizontal
        return current.add(delta.x * factor, delta.y.coerceIn(-MAX_STEP, MAX_STEP), delta.z * factor).also { center = it }
    }

    companion object {
        const val HOLD_TICKS = 200L
        private const val MAX_STEP = 0.4
    }
}

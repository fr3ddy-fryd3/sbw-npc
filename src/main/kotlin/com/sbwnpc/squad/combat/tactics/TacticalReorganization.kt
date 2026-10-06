package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3

/** Capability loss and recovery use different thresholds; interruptions preserve the episode's fixed origin. */
class TacticalReorganization {
    var active = false
        private set
    var origin: Vec3? = null
        private set
    private var stamp: Int? = null
    private var lowSince: Long? = null
    private var restoredSince: Long? = null

    fun observe(view: TacticalSnapshot): Boolean {
        if (stamp != view.stamp) {
            stamp = view.stamp
            active = false
            origin = null
            lowSince = null
            restoredSince = null
        }
        val minimum = maxOf(1, view.peakStrength / 2)
        val capable = view.fighting.size
        if (!active) {
            if (capable >= minimum || view.visible.isEmpty()) {
                lowSince = null
                return false
            }
            val since = lowSince ?: view.now.also { lowSince = it }
            if (view.members.size >= minimum && view.now - since < LOSS_CONFIRM_TICKS) return false
            active = true
            origin = view.center
        }
        val restored = capable >= maxOf(1, kotlin.math.ceil(view.peakStrength * 0.625).toInt())
        if (restored) {
            val since = restoredSince ?: view.now.also { restoredSince = it }
            if (view.now - since >= RECOVERY_CONFIRM_TICKS) {
                active = false
                origin = null
                lowSince = null
                restoredSince = null
            }
        } else {
            restoredSince = null
        }
        return active
    }

    companion object {
        const val LOSS_CONFIRM_TICKS = 40L
        const val RECOVERY_CONFIRM_TICKS = 100L
    }
}

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
    var baseline = 0
        private set
    var holding = false
        private set

    fun observe(view: TacticalSnapshot): Boolean {
        if (stamp != view.stamp) {
            stamp = view.stamp
            active = false
            origin = null
            lowSince = null
            restoredSince = null
            baseline = view.peakStrength
            holding = false
        }
        // Reinforcements can raise capability again after a settled, smaller squad resumes work.
        if (!active) baseline = maxOf(baseline, view.members.size)
        if (holding && view.members.size < baseline) {
            baseline = maxOf(1, view.members.size)
            restoredSince = null
        }
        val minimum = maxOf(1, baseline / 2)
        val capable = view.fighting.size
        if (!active) {
            if (capable >= minimum || view.visible.isEmpty()) {
                lowSince = null
                return false
            }
            val since = lowSince ?: view.now.also { lowSince = it }
            if (view.members.size >= minimum && view.now - since < LOSS_CONFIRM_TICKS) return false
            active = true
            holding = false
            origin = view.center
        }
        val restored = capable >= maxOf(1, kotlin.math.ceil(baseline * 0.625).toInt())
        if (restored) {
            val since = restoredSince ?: view.now.also { restoredSince = it }
            if (view.now - since >= RECOVERY_CONFIRM_TICKS) {
                active = false
                holding = false
                origin = null
                lowSince = null
                restoredSince = null
            }
        } else {
            restoredSince = null
        }
        return active
    }

    /** Re-evaluate survivors only after physical withdrawal finishes, never during a transient role lock. */
    fun settle(view: TacticalSnapshot): Boolean {
        if (!active) return false
        val changed = !holding || view.members.size < baseline
        holding = true
        baseline = maxOf(1, minOf(baseline, view.members.size))
        restoredSince = null
        return changed
    }

    companion object {
        const val LOSS_CONFIRM_TICKS = 40L
        const val RECOVERY_CONFIRM_TICKS = 100L
    }
}

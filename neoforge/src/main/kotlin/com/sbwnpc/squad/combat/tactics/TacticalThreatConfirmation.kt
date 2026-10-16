package com.sbwnpc.squad.combat.tactics

/** Confirm changing threat angles before interrupting a committed maneuver. Immediate dangers bypass this gate. */
class TacticalThreatConfirmation {
    private var plan: Int? = null
    private var since: Long? = null
    private var lastSeen = Long.MIN_VALUE

    fun allows(old: TacticalPlan?, choice: TacticalChoice, stamp: Int, now: Long): Boolean {
        val needsConfirmation = old != null && old.stamp == stamp &&
            (old.behavior.stability.committed || old.pattern == TacticalPattern.BREAK_CONTACT) &&
            old.status !in setOf(TacticalStatus.FAILED, TacticalStatus.COMPLETED) &&
            choice.pattern == TacticalPattern.REORIENT && choice.reason == TacticalReason.MULTIPLE_FRONTS
        if (!needsConfirmation) {
            since = null
            plan = null
            return true
        }
        if (plan != old!!.id || since == null || now - lastSeen > MAX_SAMPLE_GAP) {
            plan = old.id
            since = now
        }
        lastSeen = now
        return now - since!! >= CONFIRM_TICKS
    }

    companion object {
        const val CONFIRM_TICKS = 40L
        private const val MAX_SAMPLE_GAP = 20L
    }
}

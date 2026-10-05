package com.sbwnpc.squad.combat.tactics

enum class TacticalSelectionHold { STICKY_SECTOR, COMMITTED_MANEUVER, MINIMUM_DURATION, SAME_PLAN }
sealed interface TacticalSelection {
    data class Replace(val trigger: String) : TacticalSelection
    data class Keep(val reason: TacticalSelectionHold) : TacticalSelection
}

/** Guard evaluation is pure; only SquadTacticalState commits plan replacement. */
object TacticalSelectionPolicy {
    fun evaluate(old: TacticalPlan?, choice: TacticalChoice, stamp: Int, now: Long, registry: TacticalStateRegistry): TacticalSelection {
        if (old == null) return TacticalSelection.Replace("initial_assessment")
        if (old.stamp != stamp) return TacticalSelection.Replace("player_order_changed")
        val policy = old.behavior.stability
        val expired = old.status in setOf(TacticalStatus.FAILED, TacticalStatus.COMPLETED) ||
            (policy.canLeaveImmediately && choice.pattern != old.pattern) ||
            now - old.started >= 1200 || now - old.phaseSince >= policy.phaseLimit
        if (expired) return TacticalSelection.Replace(if (old.status in setOf(TacticalStatus.FAILED, TacticalStatus.COMPLETED)) "plan_terminal" else "plan_expired")
        val changedFocus = old.focus != null && choice.focus != null && old.focus.distanceTo(choice.focus) > 20.0
        if (policy.keepsThreatSector && choice.pattern == old.pattern) return TacticalSelection.Keep(TacticalSelectionHold.STICKY_SECTOR)
        val proposed = registry.policy(choice.pattern)
        if (!choice.emergency && policy.committed && (proposed.passive || proposed.committed))
            return TacticalSelection.Keep(TacticalSelectionHold.COMMITTED_MANEUVER)
        if (!choice.emergency && !policy.quickReplacement && now - old.started < 80)
            return TacticalSelection.Keep(TacticalSelectionHold.MINIMUM_DURATION)
        if (old.pattern == choice.pattern && !changedFocus) return TacticalSelection.Keep(TacticalSelectionHold.SAME_PLAN)
        return TacticalSelection.Replace(if (choice.emergency) "emergency_${choice.reason}" else "assessment_changed")
    }
}

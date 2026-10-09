package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.FiringPostArrival

enum class TacticalSelectionHold { STICKY_SECTOR, COMMITTED_MANEUVER, MINIMUM_DURATION, SAME_PLAN, THREAT_CONFIRMATION, FIRING_POST_RECOVERY }
sealed interface TacticalSelection {
    data class Replace(val trigger: String) : TacticalSelection
    data class Keep(val reason: TacticalSelectionHold) : TacticalSelection
}

/** Guard evaluation is pure; only SquadTacticalState commits plan replacement. */
object TacticalSelectionPolicy {
    fun evaluate(old: TacticalPlan?, choice: TacticalChoice, stamp: Int, now: Long, registry: TacticalStateRegistry, view: TacticalSnapshot? = null): TacticalSelection {
        if (old == null) return TacticalSelection.Replace("initial_assessment")
        if (old.stamp != stamp) return TacticalSelection.Replace("player_order_changed")
        val policy = old.behavior.stability
        if (old.status in TERMINAL_STATUSES) return TacticalSelection.Replace("plan_terminal")
        if (recoveringFiringPost(old, choice, view, now)) {
            return TacticalSelection.Keep(TacticalSelectionHold.FIRING_POST_RECOVERY)
        }
        if (canReplaceImmediately(old, choice, now)) return TacticalSelection.Replace("plan_expired")
        val changedFocus = old.focus != null && choice.focus != null && old.focus.distanceTo(choice.focus) > FOCUS_CHANGE_DISTANCE
        if (policy.keepsThreatSector && choice.pattern == old.pattern) return TacticalSelection.Keep(TacticalSelectionHold.STICKY_SECTOR)
        val proposed = registry.policy(choice.pattern)
        if (!choice.emergency && policy.committed && (proposed.passive || proposed.committed)) {
            return TacticalSelection.Keep(TacticalSelectionHold.COMMITTED_MANEUVER)
        }
        if (!choice.emergency && !policy.quickReplacement && now - old.started < MINIMUM_PLAN_TICKS) {
            return TacticalSelection.Keep(TacticalSelectionHold.MINIMUM_DURATION)
        }
        if (old.pattern == choice.pattern && !changedFocus) return TacticalSelection.Keep(TacticalSelectionHold.SAME_PLAN)
        return TacticalSelection.Replace(if (choice.emergency) "emergency_${choice.reason}" else "assessment_changed")
    }

    /** A normal advance must not cancel a reachable local post halfway through its approach. */
    private fun recoveringFiringPost(plan: TacticalPlan, choice: TacticalChoice, view: TacticalSnapshot?, now: Long): Boolean {
        if (choice.emergency || !plan.behavior.stability.passive || view?.offensive != true) return false
        if (now - plan.started >= FIRING_RECOVERY_LIMIT) return false
        val focus = plan.focus ?: return false
        if (choice.focus?.distanceTo(focus)?.let { it <= FOCUS_CHANGE_DISTANCE } != true) return false
        return view.members.any { member ->
            val task = plan.tasks[member.id] ?: return@any false
            val post = task.position ?: return@any false
            member.ready && task.localFireRecovery && !FiringPostArrival.reached(member.position, post) &&
                now - task.lastProgress < FIRING_RECOVERY_STALL
        }
    }

    private fun canReplaceImmediately(plan: TacticalPlan, choice: TacticalChoice, now: Long): Boolean {
        val policy = plan.behavior.stability
        val transientStateEnded = policy.canLeaveImmediately && choice.pattern != plan.pattern
        val lifetimeExpired = policy.expires && plan.behavior.lifetimeElapsed(plan.started, now) >= MAXIMUM_PLAN_TICKS
        val phaseExpired = policy.expires && now - plan.phaseSince >= policy.phaseLimit && !plan.behavior.ownsPhaseDeadline()
        return transientStateEnded || lifetimeExpired || phaseExpired
    }

    private const val FOCUS_CHANGE_DISTANCE = 20.0
    private const val MINIMUM_PLAN_TICKS = 80
    private const val MAXIMUM_PLAN_TICKS = 1200
    private const val FIRING_RECOVERY_LIMIT = 600
    private const val FIRING_RECOVERY_STALL = 100
    private val TERMINAL_STATUSES = setOf(TacticalStatus.FAILED, TacticalStatus.COMPLETED)
}

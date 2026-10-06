package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import java.util.UUID

fun interface TacticalDecisionPolicy {
    fun choose(view: TacticalSnapshot): TacticalChoice
}

data class TacticalAssessment(val proposed: TacticalChoice, val effective: TacticalChoice, val selected: Boolean, val changed: Boolean)

/** Shared observations and plan lifecycle for one squad. No entity/world references. */
class SquadTacticalState(
    observer: TacticalEventSink = TacticalEventSink.NONE,
    private val registry: TacticalStateRegistry = TacticalStates.registry,
    private val decisions: TacticalDecisionPolicy = TacticalRules,
) {
    val events = TacticalEventStream(observer)
    val contacts = LinkedHashMap<UUID, TacticalContact>()
    var snapshot: TacticalSnapshot? = null
    var plan: TacticalPlan? = null
        private set
    var nextAssessment = Long.MIN_VALUE
    var nextTrace = Long.MIN_VALUE
    val holdAfterFailure = HashMap<UUID, Long>()
    var peakStrength = 0
    var blockedUntil = Long.MIN_VALUE
    var blockedPattern: TacticalPattern? = null
    val blocked = HashMap<TacticalPattern, Long>()
    var flankSide = 0.0
    private var serial = 0
    private var lastHeld: Pair<TacticalPattern, TacticalSelectionHold>? = null
    private var nextHeldTrace = Long.MIN_VALUE
    private var selecting = false
    private val threats = TacticalThreatConfirmation()
    private val reorganization = TacticalReorganization()

    fun assess(squad: UUID, view: TacticalSnapshot): TacticalAssessment {
        val old = plan
        val oldPhase = old?.phase
        val oldBounds = old?.bounds
        val movedHome = view.order == SquadOrder.DEFEND && snapshot?.home != null && view.home != null &&
            snapshot!!.home!!.distanceTo(view.home) > 8.0
        if (movedHome) old?.complete(view.now, "defensive_home_changed")
        val wasRegrouping = reorganization.active
        val regrouping = reorganization.observe(view)
        val decisionView = view.copy(
            regrouping = regrouping,
            threatOrigin = old?.origin?.takeIf { view.offensive && old.behavior.stability.committed },
        )
        snapshot = decisionView
        if (wasRegrouping != regrouping) {
            events.emit(TacticalEvent.ReorganizationChanged(old?.id ?: 0, view.now, regrouping, reorganization.origin, view.fighting.size, view.peakStrength))
        }
        val proposed = decisions.choose(decisionView)
        val effective = if (proposed.pattern != TacticalPattern.EVADE && view.now < (blocked[proposed.pattern] ?: Long.MIN_VALUE)) {
            TacticalChoice(
                if (view.visible.isNotEmpty() || regrouping) TacticalPattern.REORIENT else TacticalPattern.FOLLOW_ORDER,
                proposed.focus,
                reason = TacticalReason.PATTERN_COOLDOWN,
            )
        } else {
            proposed
        }
        val previous = old?.tasks?.toMap().orEmpty()
        val retainDefence = !movedHome && old?.stamp == view.stamp && old.behavior.supportsDefensiveOverwatch
        val selected = select(effective, view.stamp, view.now)
        if (selected) {
            plan?.let {
                TacticalCoordinator.assign(squad, it, view, this)
                if (retainDefence) DefensiveOverwatch.preservePosts(it, previous, view)
            }
        }
        plan?.let { TacticalCoordinator.advance(squad, this, it, view) }
        return TacticalAssessment(proposed, effective, selected, selected || plan?.phase != oldPhase || plan?.bounds != oldBounds)
    }

    fun select(choice: TacticalChoice, stamp: Int, now: Long): Boolean {
        check(!selecting) { "A tactical observer cannot reenter plan selection" }
        selecting = true
        try {
            return selectPlan(choice, stamp, now)
        } finally {
            selecting = false
        }
    }

    private fun selectPlan(choice: TacticalChoice, stamp: Int, now: Long): Boolean {
        val old = plan
        val decision = if (threats.allows(old, choice, stamp, now)) {
            TacticalSelectionPolicy.evaluate(old, choice, stamp, now, registry)
        } else {
            TacticalSelection.Keep(TacticalSelectionHold.THREAT_CONFIRMATION)
        }
        if (decision is TacticalSelection.Keep) {
            val key = choice.pattern to decision.reason
            if (key != lastHeld || now >= nextHeldTrace) {
                events.emit(TacticalEvent.SelectionHeld(old!!.id, now, choice.pattern, old.pattern, decision.reason))
                lastHeld = key
                nextHeldTrace = now + 100
            }
            return false
        }
        decision as TacticalSelection.Replace
        val changedOrder = old != null && old.stamp != stamp
        // Validate the new state before cancelling any work owned by the current plan.
        val next = TacticalPlan(
            serial + 1, choice.pattern, choice.focus, now, stamp, events = events,
            behavior = registry.create(choice.pattern), reason = choice.reason, flankSide = flankSide,
        ).also {
            if (it.behavior.stability.carriesOrigin && !changedOrder) {
                it.behavior.origin = reorganization.origin?.takeIf { choice.pattern == TacticalPattern.REORGANIZE }
                    ?: old?.origin?.takeIf { old.pattern == choice.pattern }
            }
        }
        if (changedOrder) {
            blockedPattern = null
            blocked.clear()
            holdAfterFailure.clear()
        }
        old?.exit(now, decision.trigger)
        serial++
        plan = next
        events.emit(TacticalEvent.PlanSelected(next.id, now, choice, old?.id, old?.phase, stamp, decision.trigger))
        lastHeld = null
        nextHeldTrace = Long.MIN_VALUE
        return true
    }

    /** Cancels owned work; observations remain available if this squad becomes active again. */
    fun deactivate(now: Long = snapshot?.now ?: plan?.started ?: 0L, trigger: String) {
        check(!selecting) { "Cannot deactivate during plan selection" }
        val previous = plan ?: return
        selecting = true
        try {
            previous.exit(now, trigger)
            plan = null
            holdAfterFailure.clear()
            lastHeld = null
            nextHeldTrace = Long.MIN_VALUE
            nextAssessment = now
        } finally {
            selecting = false
        }
    }

    fun holdsAfterFailure(member: UUID, stamp: Int, now: Long): Boolean {
        val current = plan ?: return false
        if (current.stamp != stamp) return false
        if (current.status != TacticalStatus.FAILED && member in current.tasks) return false
        return now < (holdAfterFailure[member] ?: Long.MIN_VALUE) ||
            (
                current.behavior.holdsFailedMembers && member in current.failedMembers &&
                    (snapshot?.visible?.isNotEmpty() == true || reorganization.active)
                )
    }

    fun fail(now: Long, reason: TacticalFailure = TacticalFailure.UNSPECIFIED) {
        val current = plan ?: return
        if (current.status in setOf(TacticalStatus.COMPLETED, TacticalStatus.FAILED)) return
        blockedPattern = current.pattern
        blocked[current.pattern] = now + 120
        if (current.behavior.alternatesSideAfterFailure) flankSide = if (current.flankSide == 0.0) -1.0 else -current.flankSide
        for (id in current.tasks.keys) holdAfterFailure[id] = now + 120
        current.fail(now, reason)
        blockedUntil = now + 120
        nextAssessment = now
    }
}

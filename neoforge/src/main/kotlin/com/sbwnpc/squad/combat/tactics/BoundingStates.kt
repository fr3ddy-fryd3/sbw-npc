package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import java.util.UUID

abstract class BoundingState : CoveredManeuverState() {
    override val stability = TacticalStability(committed = true)
    open val pursuit = false
    override val canProbe get() = !pursuit
    override val assignmentRange get() = if (pursuit) 48.0 else 160.0
    override val positions = BoundingPositions
    private var movingHalf = 0
    private val groups = LinkedHashMap<UUID, Int>()
    private var reportedHold = false

    override fun requiresActualCover(context: TacticalContext): Boolean = context.view.incoming.isNotEmpty() || context.view.members.any { it.suppressed }

    override fun prepareAssignments(layout: TacticalLayout) {
        reportedHold = false
        for ((index, member) in layout.fighters.withIndex()) {
            groups.putIfAbsent(member.id, if (bounds == 0 && member.role in TacticalLayout.SUPPORT_ROLES) 1 else index % 2)
        }
    }

    override fun noMovers(context: TacticalContext) {
        if (reportedHold) return
        reportedHold = true
        context.plan.events.emit(TacticalEvent.Lifecycle(context.plan.id, context.view.now, "advance_held", context.plan.pattern, context.plan.phase, "no_safe_forward_bound"))
    }

    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val half = groups[member.id]
        val allowed = !pursuit || member.position.distanceTo(origin!!) < 32.0
        if (half != movingHalf || !allowed || layout.focus.distanceTo(layout.view.center) <= 24.0) return layout.cover(member)
        val job = if (layout.plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.ADVANCE
        var anchor = layout.view.center.add(layout.forward.scale(12.0)).add(layout.lateral(index))
        if (pursuit) anchor = TacticalLayout.towards(origin!!, anchor, 32.0)
        anchor = TacticalClearance.threats(layout.view).fold(anchor) { point, threat ->
            CombatPositionSafety.approach(member.position, point, threat, CombatPositionSafety.clearance(member.role) + CombatPositionSafety.APPROACH_MARGIN)
        }
        if (anchor.subtract(member.position).dot(layout.forward) < 1.0) return layout.cover(member)
        return layout.intent(job, anchor)
    }

    override fun movementTimedOut(context: TacticalContext, movers: List<TacticalMember>) {
        val settled = movers.filter { TacticalEvidence.settled(it, context.plan.tasks[it.id]) }
        // A completed majority can cover the following group. An immobile wave cannot.
        if (settled.isEmpty() || settled.size * 4 < movers.size * 3) {
            super.movementTimedOut(context, movers)
            return
        }
        val stragglers = movers.filter { it !in settled }
        context.plan.events.emit(TacticalEvent.WaveReduced(context.plan.id, context.view.now, bounds, settled.map { it.id }, stragglers.map { it.id }, context.plan.pattern))
        stragglers.forEach { context.tasks.abandon(it.id, context.view.now, "bound_straggler_timeout") }
        arrived(context, settled)
    }

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        bounds++
        advanceFront(context, context.view.center)
        if (bounds < 3 && (context.plan.focus?.distanceTo(context.view.center) ?: 0.0) > 24.0) {
            movingHalf = 1 - movingHalf
            phases.transition(PreparingPhase(), context, "bound_group_arrived")
        } else {
            for (member in movers) {
                context.tasks.put(
                    member.id,
                    TacticalTask(TacticalJob.COVER, member.position, context.plan.focus, context.plan.id),
                    context.view.now,
                    "bound_limit_reached",
                )
            }
            context.plan.complete(context.view.now, "bound_cycle_complete")
        }
    }
}

class BoundState : BoundingState()
class PursueState : BoundingState() {
    override val pursuit = true
    override val stability = TacticalStability(committed = true, carriesOrigin = true)
}

package com.sbwnpc.squad.combat.tactics

abstract class BoundingState : CoveredManeuverState() {
    override val stability = TacticalStability(committed = true)
    open val pursuit = false
    override val canProbe get() = !pursuit
    override val assignmentRange get() = if (pursuit) 48.0 else 160.0
    private var movingHalf = 0

    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val half = if (bounds == 0 && member.role in TacticalLayout.SUPPORT_ROLES) 1 else layout.fighters.indexOf(member).coerceAtLeast(0) % 2
        val allowed = !pursuit || member.position.distanceTo(origin!!) < 32.0
        if (half != movingHalf || !allowed || layout.focus.distanceTo(layout.view.center) <= 24.0) return layout.cover(member)
        val job = if (layout.plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.ADVANCE
        var anchor = layout.view.center.add(layout.forward.scale(12.0)).add(layout.lateral(index))
        if (pursuit) anchor = TacticalLayout.towards(origin!!, anchor, 32.0)
        return layout.intent(job, anchor)
    }

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        bounds++
        if (bounds < 3 && (context.plan.focus?.distanceTo(context.view.center) ?: 0.0) > 24.0) {
            movingHalf = 1 - movingHalf
            phases.transition(PreparingPhase(), context, "bound_group_arrived")
        } else for (member in movers) context.tasks.put(member.id,
            TacticalTask(TacticalJob.COVER, member.position, context.plan.focus, context.plan.id), context.view.now, "bound_limit_reached")
    }
}

class BoundState : BoundingState()
class PursueState : BoundingState() {
    override val pursuit = true
    override val stability = TacticalStability(committed = true, carriesOrigin = true)
}

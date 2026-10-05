package com.sbwnpc.squad.combat.tactics

abstract class WithdrawalState : CoveredManeuverState() {
    override val positions = WithdrawalPositions
    override val withdrawal = true
    open val limited = false
    open val avoidsArmour = false
    private var movingHalf = 0

    override fun readyWithoutCover(context: TacticalContext): Boolean = avoidsArmour || context.view.now - phaseSince >= 40
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val half = layout.fighters.indexOf(member).coerceAtLeast(0) % 2
        var job = if (half != movingHalf && member.ready && !avoidsArmour) TacticalJob.COVER
            else if (layout.plan.status == TacticalStatus.PREPARING && !avoidsArmour) TacticalJob.WAIT else TacticalJob.RETREAT
        var anchor = if (job == TacticalJob.COVER) member.position
            else layout.view.center.add(layout.escape().scale(12.0)).add(layout.lateral(index))
        if (limited) {
            anchor = TacticalLayout.towards(origin!!, anchor, 32.0)
            if (member.position.distanceTo(origin!!) >= 30.0) { job = TacticalJob.COVER; anchor = member.position }
        }
        return layout.intent(job, anchor)
    }

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        if (!avoidsArmour && ++bounds < 3 && (context.plan.focus?.distanceTo(context.view.center) ?: 0.0) > 24.0) {
            movingHalf = 1 - movingHalf
            phases.transition(PreparingPhase(), context, "withdrawal_group_arrived")
        } else for (member in movers) context.tasks.put(member.id,
            TacticalTask(TacticalJob.COVER, member.position, context.plan.focus, context.plan.id), context.view.now, "withdrawal_limit_reached")
    }
}

class ReorganizeState : WithdrawalState() {
    override val limited = true
    override val stability = TacticalStability(carriesOrigin = true)
}
class BreakContactState : WithdrawalState()
class AvoidArmourState : WithdrawalState() { override val avoidsArmour = true }

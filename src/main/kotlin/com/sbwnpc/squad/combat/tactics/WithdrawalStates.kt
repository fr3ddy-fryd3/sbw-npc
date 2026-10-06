package com.sbwnpc.squad.combat.tactics

abstract class WithdrawalState : CoveredManeuverState() {
    override val positions = WithdrawalPositions
    override val withdrawal = true
    open val limited = false
    open val avoidsArmour = false
    private var movingHalf = 0
    private val halves = HashMap<java.util.UUID, Int>()

    override fun prepareAssignments(layout: TacticalLayout) {
        for (member in layout.fighters) halves.getOrPut(member.id) { halves.size % 2 }
    }

    override fun readyWithoutCover(context: TacticalContext): Boolean = avoidsArmour || context.view.now - phaseSince >= 40
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val half = halves.getOrPut(member.id) { halves.size % 2 }
        var job = if (half != movingHalf && member.ready && !avoidsArmour) {
            TacticalJob.COVER
        } else if (layout.plan.status == TacticalStatus.PREPARING && !avoidsArmour) {
            TacticalJob.WAIT
        } else {
            TacticalJob.RETREAT
        }
        var anchor = if (job == TacticalJob.COVER) {
            member.position
        } else {
            layout.view.center.add(layout.escape().scale(12.0)).add(layout.lateral(index))
        }
        if (limited) {
            anchor = TacticalLayout.towards(origin!!, anchor, 32.0)
            if (member.position.distanceTo(origin!!) >= 30.0) {
                job = TacticalJob.COVER
                anchor = member.position
            }
        }
        return layout.intent(job, anchor)
    }

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        if (!avoidsArmour && ++bounds < 3 && (context.plan.focus?.distanceTo(context.view.center) ?: 0.0) > 24.0) {
            movingHalf = 1 - movingHalf
            phases.transition(PreparingPhase(), context, "withdrawal_group_arrived")
        } else {
            holdMovers(context, movers, "withdrawal_limit_reached")
        }
    }

    protected open fun holdMovers(context: TacticalContext, movers: List<TacticalMember>, reason: String) {
        for (member in movers) {
            context.tasks.put(member.id, TacticalTask(TacticalJob.COVER, member.position, context.plan.focus, context.plan.id), context.view.now, reason)
        }
    }
}

class ReorganizeState : WithdrawalState() {
    override val limited = true
    override val holdsFailedMembers = true
    override val stability = TacticalStability(carriesOrigin = true, keepsThreatSector = true, expires = false)
    private val holding = HashSet<java.util.UUID>()

    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        if (member.position.distanceTo(origin!!) >= 30.0) holding.add(member.id)
        return if (member.id in holding) layout.cover(member) else super.task(layout, member, index)
    }

    override fun holdMovers(context: TacticalContext, movers: List<TacticalMember>, reason: String) {
        holding.addAll(movers.map { it.id })
        super.holdMovers(context, movers, reason)
    }

    override fun movementTimedOut(context: TacticalContext, movers: List<TacticalMember>) {
        holdMovers(context, movers, "regroup_movement_timeout_hold")
    }
}
class BreakContactState : WithdrawalState()
class AvoidArmourState : WithdrawalState() {
    override val avoidsArmour = true
}

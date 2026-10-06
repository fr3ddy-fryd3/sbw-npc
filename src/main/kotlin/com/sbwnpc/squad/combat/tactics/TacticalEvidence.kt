package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.FiringPostArrival

/** Readiness is separate from actual fire, and both refer to the assigned threat sector. */
object TacticalEvidence {
    fun covers(member: TacticalMember, task: TacticalTask?): Boolean {
        val focus = task?.focus
        return member.canFire && (member.firingAt == null || focus == null || member.firingAt.distanceTo(focus) <= 18.0)
    }

    fun firesCover(member: TacticalMember, task: TacticalTask?): Boolean =
        member.recentFire && covers(member, task) &&
            (member.recentFireAt == null || task?.focus == null || member.recentFireAt.distanceTo(task.focus!!) <= 18.0)

    fun settled(member: TacticalMember, task: TacticalTask?): Boolean =
        task?.position?.let {
            (if (task.job in TacticalJob.FIRING_POSITIONS || task.job == TacticalJob.FLANK) FiringPostArrival.reached(member.position, it) else member.position.distanceTo(it) <= 2.5) &&
                (task.job in TacticalJob.FIRE_SUPPORT || member.position.distanceTo(task.anchor) <= 10.0)
        } == true

    fun transition(plan: TacticalPlan, view: TacticalSnapshot): TacticalTransitionEvidence {
        val cover = view.members.filter { plan.tasks[it.id]?.job in TacticalJob.FIRE_SUPPORT }
        val movers = view.members.filter { plan.tasks[it.id]?.job in setOf(TacticalJob.FLANK, TacticalJob.ADVANCE, TacticalJob.RETREAT) }
        return TacticalTransitionEvidence(
            view.members.size,
            view.visible.size,
            cover.size,
            cover.count { covers(it, plan.tasks[it.id]) },
            cover.count { firesCover(it, plan.tasks[it.id]) },
            movers.size,
            movers.count { settled(it, plan.tasks[it.id]) },
        )
    }
}

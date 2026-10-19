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
            !task.deployingFront(member.position) &&
                (if (task.job in TacticalJob.FIRING_POSITIONS) FiringPostArrival.reached(member.position, it) else member.position.distanceTo(it) <= 2.5) &&
                (task.job in TacticalJob.FIRE_SUPPORT || TacticalGoalProgress.reached(task, member.position))
        } == true
}

package com.sbwnpc.squad.combat.tactics

/** Diagnostics distinguish an available firing lane from shots actually fired in that sector. */
object TacticalTelemetry {
    data class Cover(val assigned: Int, val ready: Int, val settledReady: Int, val firedRecently: Int)

    fun cover(plan: TacticalPlan,view: TacticalSnapshot): Cover {
        val members=view.members.filter { plan.tasks[it.id]?.job == TacticalJob.COVER }
        return Cover(members.size,
            members.count { TacticalManeuvers.covers(it,plan.tasks[it.id]) },
            members.count { TacticalManeuvers.covers(it,plan.tasks[it.id]) && TacticalManeuvers.settled(it,plan.tasks[it.id]) },
            members.count { member ->
                val focus=plan.tasks[member.id]?.focus
                val firedAt=member.recentFireAt
                member.recentFire && firedAt != null && (focus==null || firedAt.distanceTo(focus)<=18.0)
            })
    }
}

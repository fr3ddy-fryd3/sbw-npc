package com.sbwnpc.squad.combat.tactics

import java.util.UUID

/** Pure entry points used by the world adapter and the same scenario tests. */
object TacticalCoordinator {
    fun assign(squadId: UUID, plan: TacticalPlan, view: TacticalSnapshot, owner: SquadTacticalState? = null) =
        plan.assign(squadId, view, owner)
    fun advance(squadId: UUID, state: SquadTacticalState, plan: TacticalPlan, view: TacticalSnapshot) = plan.tick(squadId, state, view)
    fun refreshSectors(plan: TacticalPlan, view: TacticalSnapshot) = plan.refreshSectors(view)
    fun abandon(plan: TacticalPlan, member: UUID, now: Long = plan.phaseSince, reason: String = "route_failed") =
        plan.assignments.abandon(member, now, reason)
}

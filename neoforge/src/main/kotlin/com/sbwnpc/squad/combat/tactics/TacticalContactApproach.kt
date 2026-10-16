package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3

/** A rear shooter joins the contact's firing area rather than stopping at a distant median. */
object TacticalContactApproach {
    fun post(view: TacticalSnapshot, member: TacticalMember, assigned: TacticalFrontPost, focus: Vec3?): TacticalFrontPost {
        if (!view.offensive || member.canFire || !member.primaryAmmo || focus == null) return assigned
        val distance = maxOf(member.role.assaultDistance, CombatPositionSafety.clearance(member.role) + CombatPositionSafety.APPROACH_MARGIN)
        val remaining = focus.subtract(assigned.point).dot(assigned.forward)
        // Nearby first contacts keep the original shelter search. Joining a distant report
        // is a separate transit problem, not a reason to walk past usable local cover.
        if (remaining <= distance + JOIN_MARGIN || focus.subtract(member.position).horizontalDistance() <= distance + JOIN_MARGIN) return assigned
        // Preserve lateral ownership and never recall a leading member. Navigation still
        // validates every short approach leg against all contacts, terrain and role locks.
        val point = assigned.point.add(assigned.forward.scale(remaining - distance - POST_MARGIN))
        return assigned.copy(point = point)
    }

    /** Local contact shelter is useful within fighting distance or under actual incoming fire. */
    fun searchesOriginShelter(view: TacticalSnapshot, role: NpcClass, from: Vec3): Boolean =
        view.incoming.isNotEmpty() || view.members.any { it.suppressed } ||
            TacticalClearance.threats(view).any { it.subtract(from).horizontalDistance() <= role.assaultDistance + JOIN_MARGIN }

    private const val POST_MARGIN = 8.0
    private const val JOIN_MARGIN = 48.0
}

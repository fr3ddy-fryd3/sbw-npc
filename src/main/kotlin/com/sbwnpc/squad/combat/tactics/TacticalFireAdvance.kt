package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import net.minecraft.world.phys.Vec3

/** An exhausted blind firing disk needs a new origin, not another identical scan. */
internal object TacticalFireAdvance {
    fun begin(task: TacticalTask, member: TacticalMember, view: TacticalSnapshot, plan: TacticalPlan): Boolean {
        if (!view.offensive || !member.ready || !member.primaryAmmo || member.suppressed || member.canFire ||
            task.job !in TacticalJob.FIRE_SUPPORT || task.frontage == null || !task.expandedFireSearch || task.fireTransit != null
        ) {
            return false
        }
        val threats = TacticalClearance.threats(view)
        if (threats.isEmpty()) return false
        val underFire = view.incoming.isNotEmpty() || view.members.any { it.suppressed }
        if (underFire && view.members.none {
                it.id != member.id && TacticalEvidence.firesCover(it, plan.tasks[it.id]) &&
                    it.recentFireAt?.let { point -> task.focus == null || point.distanceTo(task.focus!!) <= 18.0 } == true
            }
        ) {
            return false
        }
        val forward = task.frontage!!.forward
        val desired = member.position.add(forward.scale(if (underFire) 4.0 else 12.0))
        val goal = threats.fold(desired) { point, threat ->
            CombatPositionSafety.approach(member.position, point, threat, CombatPositionSafety.clearance(member.role) + CombatPositionSafety.APPROACH_MARGIN)
        }
        if (goal.subtract(member.position).dot(forward) < 2.0) return false
        task.fireTransit = goal
        task.position = null
        task.search = null
        task.nextSearch = view.now
        return true
    }

    /** A short transit can be blind, but must physically advance within this actor's lane. */
    fun permits(task: TacticalTask, from: Vec3, point: Vec3): Boolean {
        val goal = task.fireTransit ?: return true
        val forward = task.frontage!!.forward
        val side = Vec3(-forward.z, 0.0, forward.x)
        return point.subtract(from).dot(forward) >= 1.0 &&
            kotlin.math.abs(point.subtract(from).dot(side)) <= TacticalFrontPost.LATERAL_TOLERANCE &&
            point.subtract(goal).horizontalDistance() < from.subtract(goal).horizontalDistance() - 0.5 &&
            point.subtract(from).horizontalDistance() <= 16.0
    }
}

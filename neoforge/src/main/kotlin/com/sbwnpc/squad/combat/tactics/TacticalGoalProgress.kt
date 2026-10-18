package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.FiringPostArrival
import net.minecraft.world.phys.Vec3

/** Intermediate advance posts must reduce the remaining route, rather than renew an idle task. */
object TacticalGoalProgress {
    fun reached(task: TacticalTask, point: Vec3): Boolean = point.distanceTo(task.anchor) <= GOAL_RADIUS

    fun permitsAdvance(task: TacticalTask, origin: Vec3, point: Vec3): Boolean {
        if (task.job !in setOf(TacticalJob.ADVANCE, TacticalJob.SEARCH, TacticalJob.RESERVE, TacticalJob.OBSERVE)) return true
        if (reached(task, point)) return true
        return !FiringPostArrival.reached(origin, point) &&
            point.distanceTo(task.anchor) < origin.distanceTo(task.anchor) - MINIMUM_GAIN
    }

    private const val GOAL_RADIUS = 10.0
    private const val MINIMUM_GAIN = 0.5
}

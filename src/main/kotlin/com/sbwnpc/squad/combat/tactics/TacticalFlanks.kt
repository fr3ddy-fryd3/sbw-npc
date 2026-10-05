package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Pure flank geometry and wave membership; state classes select the geometry. */
object TacticalFlanks {
    val PATTERNS get() = TacticalStates.registry.flankingPatterns
    const val TEAM_SIZE = 6

    fun pending(plan: TacticalPlan, view: TacticalSnapshot): List<UUID> {
        val state = plan.behavior as FlankingState
        val alive = view.fighting.map { it.id }.toSet()
        return state.order.filter { it in alive && it !in state.arrived && it !in plan.failedMembers &&
            it !in state.support && it != state.medicGuard }
    }

    fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int): Vec3 =
        (plan.behavior as FlankingState).anchor(plan, view, slot)

    fun shoulder(plan: TacticalPlan, view: TacticalSnapshot, slot: Int, weakSector: Boolean = false): Vec3 {
        val state = plan.behavior as FlankingState
        val origin = plan.origin ?: view.center
        val focus = plan.focus ?: view.home ?: origin
        val forward = forward(origin, focus)
        val side = Vec3(-forward.z, 0.0, forward.x)
        val projections = view.contacts.map { it.position.subtract(focus).dot(side) }
        val left = minOf(-20.0, (projections.minOrNull() ?: 0.0) - 12.0)
        val right = maxOf(20.0, (projections.maxOrNull() ?: 0.0) + 12.0)
        val both = state.order.size >= 8 && !weakSector
        val weak = if (kotlin.math.abs(left) <= right) -1.0 else 1.0
        val direction = if (both) (if (slot % 2 == 0) -1.0 else 1.0) else if (weakSector) weak
            else if (plan.flankSide < 0.0) -1.0 else 1.0
        val lane = slot / (if (both) 2 else 1)
        val sideways = (if (direction < 0) left else right) + direction * (lane % 3) * 4.0
        val depth = maxOf(10.0, focus.subtract(origin).horizontalDistance() - 20.0 - (lane / 3) * 4.0)
        return origin.add(forward.scale(depth)).add(side.scale(sideways))
    }

    fun encircle(plan: TacticalPlan, view: TacticalSnapshot, slot: Int): Vec3 {
        val state = plan.behavior as FlankingState
        val origin = plan.origin ?: view.center
        val focus = plan.focus ?: view.home ?: origin
        val forward = forward(origin, focus)
        val side = Vec3(-forward.z, 0.0, forward.x)
        val wing = if (slot % 2 == 0) 1.0 else -1.0
        val progress = (slot / 2 + 1).toDouble() / ((state.order.size + 1) / 2 + 1)
        val angle = Math.PI + wing * progress * Math.PI * 0.85
        return focus.add(forward.scale(kotlin.math.cos(angle) * 24.0)).add(side.scale(kotlin.math.sin(angle) * 24.0))
    }

    private fun forward(origin: Vec3, focus: Vec3): Vec3 = focus.subtract(origin).multiply(1.0, 0.0, 1.0).normalize()
        .let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }

    fun requiredCover(view: TacticalSnapshot): Int = if (view.fighting.size >= 12) 2 else 1
}

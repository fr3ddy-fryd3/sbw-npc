package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Pure flank geometry and wave membership; state classes select the geometry. */
object TacticalFlanks {
    val PATTERNS get() = TacticalStates.registry.flankingPatterns
    const val TEAM_SIZE = 6

    fun pending(plan: TacticalPlan, view: TacticalSnapshot): List<UUID> {
        val state = plan.behavior as FlankingState
        val alive = view.fighting.map { it.id }.toSet()
        val focus = plan.focus ?: view.center
        return state.order.filter {
            it in alive && it !in state.arrived && it !in plan.failedMembers &&
                it !in state.support && it != state.medicGuard
        }.sortedByDescending { id ->
            kotlin.math.abs(anchor(plan, view, state.order.indexOf(id)).subtract(focus).dot(plan.frontage.side))
        }
    }

    fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int): Vec3 = (plan.behavior as FlankingState).anchor(plan, view, slot)

    /** Match both spatial orders before launching waves, so UUID order cannot swap left/right wings. */
    internal fun spatialOrder(plan: TacticalPlan, view: TacticalSnapshot): List<UUID> {
        val state = plan.behavior as FlankingState
        val side = plan.frontage.side
        val slots = state.order.indices.sortedBy { anchor(plan, view, it).dot(side) }
        val members = state.order.sortedWith(
            compareBy<UUID> { id ->
                (plan.frontage.offset(id) ?: view.members.first { it.id == id }.position).dot(side)
            }.thenBy { it },
        )
        val assigned = state.order.toMutableList()
        for ((member, slot) in members.zip(slots)) assigned[slot] = member
        return assigned
    }

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
        val direction = if (both) {
            (if (slot % 2 == 0) -1.0 else 1.0)
        } else if (weakSector) {
            weak
        } else if (plan.flankSide < 0.0) {
            -1.0
        } else {
            1.0
        }
        val lane = slot / (if (both) 2 else 1)
        val sideways = (if (direction < 0) left else right) + direction * lane * SquadFormation.COMBAT_SPACING
        val member = view.members.firstOrNull { it.id == state.order.getOrNull(slot) }
        val clearance = CombatPositionSafety.clearance(member?.role ?: NpcClass.RIFLEMAN) + CombatPositionSafety.FLANK_MARGIN
        val depth = focus.subtract(origin).horizontalDistance() - clearance
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
        val member = view.members.firstOrNull { it.id == state.order.getOrNull(slot) }
        val clearance = CombatPositionSafety.clearance(member?.role ?: NpcClass.RIFLEMAN) + CombatPositionSafety.FLANK_MARGIN
        val direction = forward.scale(kotlin.math.cos(angle)).add(side.scale(kotlin.math.sin(angle)))
        val radius = encircleRadius(focus, direction, clearance, TacticalClearance.threats(view))
        return focus.add(direction.scale(radius))
    }

    /** Move outward only when the intended radius intersects another known enemy's clearance disk. */
    private fun encircleRadius(focus: Vec3, direction: Vec3, clearance: Double, threats: List<Vec3>): Double {
        val intervals = threats.mapNotNull { threat ->
            val relative = threat.subtract(focus).multiply(1.0, 0.0, 1.0)
            val along = relative.dot(direction)
            val acrossSquared = relative.lengthSqr() - along * along
            if (acrossSquared >= clearance * clearance) return@mapNotNull null
            val halfChord = kotlin.math.sqrt(clearance * clearance - acrossSquared)
            (along - halfChord) to (along + halfChord)
        }.sortedBy { it.first }
        var radius = clearance
        for ((enter, exit) in intervals) {
            if (radius >= enter && radius < exit) radius = exit
        }
        return radius
    }

    private fun forward(origin: Vec3, focus: Vec3): Vec3 =
        focus.subtract(origin).multiply(1.0, 0.0, 1.0).normalize()
            .let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }

    fun requiredCover(view: TacticalSnapshot): Int = if (view.fighting.size >= 12) 2 else 1
}

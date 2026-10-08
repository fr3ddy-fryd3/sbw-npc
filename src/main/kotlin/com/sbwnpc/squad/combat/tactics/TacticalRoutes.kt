package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3
import kotlin.math.*

/** Short route legs prevent a rear flank destination from routing straight through the enemy. */
object TacticalRoutes {
    private val DETOUR_DISTANCES = listOf(24.0, 16.0, 8.0)
    private const val DETOUR_DIRECTIONS = 24
    private const val CLEARANCE_TOLERANCE = 0.05

    /** Nearby alternatives must respect every contact, including one closer than the focus. */
    fun flankProbes(from: Vec3, task: TacticalTask, view: TacticalSnapshot, role: NpcClass): List<Vec3> {
        val preferred = leg(from, task)
        val threats = TacticalClearance.threats(view)
        val clearance = CombatPositionSafety.clearance(role)
        val direction = preferred.subtract(from).multiply(1.0, 0.0, 1.0).normalize()
        if (direction.lengthSqr() < 1.0e-6) return emptyList()
        val candidates = ArrayList<Vec3>()
        for (distance in DETOUR_DISTANCES) {
            for (turn in 0 until DETOUR_DIRECTIONS) {
                val angle = turn * 2.0 * Math.PI / DETOUR_DIRECTIONS
                val rotated = Vec3(direction.x * cos(angle) - direction.z * sin(angle), 0.0, direction.x * sin(angle) + direction.z * cos(angle))
                val point = from.add(rotated.scale(distance))
                if (threats.all { CombatPositionSafety.permitsSegment(from, from, point, it, clearance, CLEARANCE_TOLERANCE) }) candidates += point
            }
        }
        return candidates.sortedBy { it.distanceTo(task.anchor) + it.distanceTo(from) }
    }

    /** A firing-lane probe opens the angle without becoming an unsupported charge. */
    fun safeLaneProbe(from: Vec3, point: Vec3, focus: Vec3, approach: Double = 4.0): Boolean =
        point.subtract(from).horizontalDistance() <= 16.0 &&
            point.subtract(focus).horizontalDistance() >= from.subtract(focus).horizontalDistance() - approach

    fun leg(from: Vec3, task: TacticalTask): Vec3 {
        val focus = task.focus
        if (task.job == TacticalJob.FLANK && focus != null) {
            val a = from.subtract(focus).multiply(1.0, 0.0, 1.0)
            val b = task.anchor.subtract(focus).multiply(1.0, 0.0, 1.0)
            if (a.lengthSqr() > 64.0 && b.lengthSqr() > 64.0) {
                val angle = acos(a.normalize().dot(b.normalize()).coerceIn(-1.0, 1.0))
                if (angle > Math.PI / 3.0) {
                    val step = minOf(Math.PI / 4.0, 2.0 * asin((24.0 / (2.0 * a.length())).coerceAtMost(1.0)))
                    val turn = if (a.x * b.z - a.z * b.x >= 0.0) step else -step
                    val unit = a.normalize()
                    val rotated = Vec3(unit.x * cos(turn) - unit.z * sin(turn), 0.0, unit.x * sin(turn) + unit.z * cos(turn))
                    return Vec3(focus.x, from.y, focus.z).add(rotated.scale(a.length()))
                }
            }
        }
        val delta = task.anchor.subtract(from)
        return if (delta.length() > 24.0) from.add(delta.normalize().scale(24.0)) else task.anchor
    }
}

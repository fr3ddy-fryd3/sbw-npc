package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3

/** Shared range and movement limits for infantry holding ground or patrolling. */
object CombatPosition {
    const val LOCAL_MOVE_RADIUS = 12.0
    const val MAX_ADVANCE_DISTANCE = 24.0

    fun holdsPosition(order: SquadOrder?): Boolean = order == SquadOrder.DEFEND || order == SquadOrder.PATROL

    fun mayAdvance(order: SquadOrder?, cls: NpcClass, from: Vec3, target: Vec3): Boolean {
        if (!holdsPosition(order)) return true
        val range = cls.assaultDistance * 2.0
        return from.distanceToSqr(target) > range * range
    }

    /** One bounded approach from the original post, stopping at the doubled assault range. */
    fun advancePoint(cls: NpcClass, post: Vec3, target: Vec3): Vec3? {
        val toward = Vec3(target.x - post.x, 0.0, target.z - post.z)
        val distance = toward.length()
        val remaining = distance - cls.assaultDistance * 2.0
        if (remaining <= 0.0) return null
        return post.add(toward.scale(minOf(remaining, MAX_ADVANCE_DISTANCE) / distance))
    }

    /** Combat bounds walk only a short leg before stopping to fire, so do not plan a long route. */
    fun assaultStep(from: Vec3, slot: Vec3): Vec3 {
        val delta = Vec3(slot.x - from.x, 0.0, slot.z - from.z)
        val distance = delta.length()
        if (distance <= MAX_ADVANCE_DISTANCE) return slot
        return from.add(delta.scale(MAX_ADVANCE_DISTANCE / distance))
    }

    fun defendRadius(cls: NpcClass, squadSize: Int): Double =
        maxOf(SquadFormation.perimeterRadius(squadSize), cls.attackStandoffDistance) + LOCAL_MOVE_RADIUS

    fun withinArea(center: Vec3, point: Vec3, radius: Double): Boolean {
        val dx = center.x - point.x
        val dz = center.z - point.z
        return dx * dx + dz * dz <= radius * radius
    }
}

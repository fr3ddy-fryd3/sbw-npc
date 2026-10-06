package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs

/** Stable lateral ownership for an attack. Target switches, phases and losses do not renumber survivors. */
class TacticalFrontage {
    private var stamp: Int? = null
    private val slots = LinkedHashMap<UUID, Double>()
    private var origin: Vec3? = null
    private var lattice = 0.0
    val initialized get() = origin != null
    var forward = Vec3(0.0, 0.0, 1.0)
        private set
    val side get() = Vec3(-forward.z, 0.0, forward.x)
    val halfWidth get() = slots.values.maxOfOrNull { abs(it) } ?: 0.0

    fun observe(view: TacticalSnapshot) {
        if (!view.offensive || stamp != view.stamp) {
            slots.clear()
            origin = null
            stamp = view.stamp
        }
        if (!view.offensive) return
        val fighters = view.members.filter { it.role != NpcClass.MEDIC }
        if (fighters.isEmpty()) return
        if (origin == null) {
            forward = direction(view) ?: return
            origin = view.center
            val ordered = fighters.sortedWith(compareBy<TacticalMember> { it.position.dot(side) }.thenBy { it.id })
            lattice = SquadFormation.lineOffset(0, ordered.size).x
            for ((index, member) in ordered.withIndex()) {
                slots[member.id] = SquadFormation.lineOffset(index, ordered.size).x
            }
        }
        val alive = fighters.map { it.id }.toSet()
        slots.keys.retainAll(alive)
        for (member in fighters.filter { it.id !in slots }) {
            val desired = member.position.subtract(origin!!).dot(side)
            val free = (-view.peakStrength..view.peakStrength).map { lattice + it * SquadFormation.COMBAT_SPACING }
                .filter { proposed -> slots.values.all { abs(it - proposed) >= SquadFormation.COMBAT_SPACING - 0.01 } }
                .minByOrNull { abs(it - desired) } ?: (halfWidth + SquadFormation.COMBAT_SPACING)
            slots[member.id] = free
        }
    }

    fun offset(member: UUID): Vec3? = slots[member]?.let { side.scale(it) }

    fun offsetFrom(member: UUID, center: Vec3): Vec3? {
        val start = origin ?: return null
        return offset(member)?.add(side.scale(start.subtract(center).dot(side)))
    }

    /** Only entering a reorientation state may rotate a developed front; individual targets cannot. */
    fun reorient(view: TacticalSnapshot) {
        observe(view)
        val next = direction(view) ?: return
        if (next.dot(forward) >= kotlin.math.cos(Math.PI / 4.0)) return
        origin = null
        slots.clear()
        observe(view)
    }

    private fun direction(view: TacticalSnapshot): Vec3? {
        val focus = view.contacts.takeIf { it.isNotEmpty() }?.let { contacts ->
            contacts.fold(Vec3.ZERO) { sum, contact -> sum.add(contact.position) }.scale(1.0 / contacts.size)
        } ?: view.home ?: return null
        return focus.subtract(view.center).multiply(1.0, 0.0, 1.0).normalize()
            .let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }
    }

    fun post(member: UUID, center: Vec3): TacticalFrontPost? {
        val start = origin ?: return null
        val offset = offset(member) ?: return null
        // The common depth follows the maneuver; the lateral axis stays fixed throughout the order.
        val depth = center.subtract(start).dot(forward)
        val baseline = start.add(forward.scale(depth))
        return TacticalFrontPost(Vec3(baseline.x, center.y, baseline.z).add(offset), forward)
    }
}

/** Terrain can adjust height and a few blocks of depth, but cannot merge neighboring firing lanes. */
data class TacticalFrontPost(val point: Vec3, val forward: Vec3) {
    private val side = Vec3(-forward.z, 0.0, forward.x)

    fun contains(candidate: Vec3): Boolean = permits(candidate, recovery = false)

    /** A failed firing query may adjust depth without taking a neighboring member's lane. */
    fun permits(candidate: Vec3, recovery: Boolean): Boolean {
        val delta = candidate.subtract(point)
        val depth = if (recovery) RECOVERY_DEPTH else DEPTH_TOLERANCE
        return abs(delta.dot(side)) <= LATERAL_TOLERANCE + POSITION_EPSILON && abs(delta.dot(forward)) <= depth + POSITION_EPSILON
    }

    fun acceptsDeployment(from: Vec3, candidate: Vec3, recovery: Boolean = false): Boolean = permits(candidate, recovery) || candidate.subtract(point).horizontalDistance() < from.subtract(point).horizontalDistance() - 0.5

    fun leg(from: Vec3): Vec3 = TacticalLayout.towards(from, point, DEPLOYMENT_LEG_BLOCKS)

    /** Local block-sized probes in this member's strip, rather than overlapping circular searches. */
    fun probes(recovery: Boolean = false): List<Vec3> {
        val reach = (if (recovery) RECOVERY_DEPTH else DEPTH_TOLERANCE).toInt()
        return (-reach..reach).sortedBy { abs(it) }.flatMap { depth ->
            // Half-block lateral probes survive rounding to block centers on diagonal fronts.
            listOf(0.0, -0.5, 0.5).map { lateral -> point.add(forward.scale(depth.toDouble())).add(side.scale(lateral)) }
        }
    }

    companion object {
        const val LATERAL_TOLERANCE = 1.0
        const val DEPTH_TOLERANCE = 2.0
        const val RECOVERY_DEPTH = 12.0
        const val DEPLOYMENT_LEG_BLOCKS = 24.0
        private const val POSITION_EPSILON = 1.0e-6
    }
}

package com.sbwnpc.squad.combat.tactics.operation

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.tactics.TacticalMember
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/** Strategy owns force allocation and geometry; the operation owns its lifetime. */
interface AttackDistribution {
    fun distribute(forces: List<AttackForce>, focus: Vec3): List<AttackLane>
    fun reinforce(force: AttackForce, central: AttackForce, occupied: Set<AttackRole>, focus: Vec3): AttackLane
}

class AttackLane internal constructor(
    val squad: UUID,
    val stamp: Int,
    val role: AttackRole,
    var corridor: AttackCorridor,
    initialSlots: Map<UUID, Double>,
    val route: MutableList<Vec3>,
) {
    private val mutableSlots = initialSlots.toMutableMap()
    private val slotCells = initialSlots.values.toMutableSet()
    val slots: Map<UUID, Double> get() = mutableSlots
    var next = 1
        internal set
    var revision = 0
        internal set
    var blocked = false
        internal set
    internal var closest = Double.POSITIVE_INFINITY
    internal var progressAt = Long.MIN_VALUE
    val sector get() = route.last()
    val deployed get() = next >= route.size
    val waypoint get() = route.getOrElse(next) { sector }

    internal fun reconcileMembers(force: AttackForce, facing: Vec3) {
        val members = force.members.filter { it.role != NpcClass.MEDIC }
        val alive = members.mapTo(HashSet()) { it.id }
        mutableSlots.keys.retainAll(alive)
        val side = Vec3(-facing.z, 0.0, facing.x)
        for (member in members.filter { it.id !in mutableSlots }) {
            val desired = member.position.subtract(sector).dot(side)
            val vacant = slotCells.filter { it !in mutableSlots.values }.minByOrNull { kotlin.math.abs(it - desired) }
            val added = vacant ?: listOf((slotCells.minOrNull() ?: 0.0) - SquadFormation.COMBAT_SPACING, (slotCells.maxOrNull() ?: 0.0) + SquadFormation.COMBAT_SPACING).minBy { kotlin.math.abs(it - desired) }
            slotCells += added
            mutableSlots[member.id] = added
        }
        val width = (mutableSlots.values.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0) + 6.0
        if (width > corridor.halfWidth) corridor = corridor.copy(halfWidth = width)
    }
}

object CenterAndWings : AttackDistribution {
    override fun reinforce(force: AttackForce, central: AttackForce, occupied: Set<AttackRole>, focus: Vec3): AttackLane {
        val role = listOf(AttackRole.LEFT, AttackRole.RIGHT).firstOrNull { it !in occupied } ?: AttackRole.RESERVE
        return lane(force, central, role, focus)
    }
    override fun distribute(forces: List<AttackForce>, focus: Vec3): List<AttackLane> {
        require(forces.size >= 2)
        // Count real primary-armed fighters, not saved roster slots or pistol-only reserves.
        val ordered = forces.sortedWith(
            compareByDescending<AttackForce> { it.fighters.size }
                .thenByDescending { it.fighters.sumOf { member -> member.health } }
                .thenBy { candidate -> forces.sumOf { it.center.distanceTo(candidate.center) } }
                .thenBy { it.center.distanceTo(focus) }
                .thenBy { it.id },
        )
        val central = ordered.first()
        val origin = central.center
        val forward = focus.subtract(origin).multiply(1.0, 0.0, 1.0).normalize().let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }
        val side = Vec3(-forward.z, 0.0, forward.x)
        val remaining = ordered.drop(1)
        val wings = remaining.take(2).sortedWith(compareBy<AttackForce> { it.center.subtract(origin).dot(side) }.thenBy { it.id })
        val selected = listOf(central to AttackRole.CENTER) + wings.mapIndexed { index, force -> force to if (index == 0) AttackRole.LEFT else AttackRole.RIGHT } +
            remaining.filter { force -> wings.none { it.id == force.id } }.map { it to AttackRole.RESERVE }
        return selected.map { (force, role) -> lane(force, central, role, focus) }
    }

    internal fun lane(force: AttackForce, central: AttackForce, role: AttackRole, focus: Vec3): AttackLane {
        val origin = central.center
        val forward = focus.subtract(origin).multiply(1.0, 0.0, 1.0).normalize().let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }
        val side = Vec3(-forward.z, 0.0, forward.x)
        // A close first contact must not recall the central squad to a nominal firing distance.
        val radius = focus.subtract(origin).horizontalDistance().coerceIn(1.0, 112.0)
        val width = halfWidth(force)
        val sign = if (role == AttackRole.LEFT) -1.0 else 1.0
        val angle = (atan(halfWidth(central) / radius) + atan(width / radius) + Math.toRadians(10.0)).coerceIn(Math.PI / 4.0, Math.toRadians(80.0))
        val goal = when (role) {
            AttackRole.CENTER -> focus.subtract(forward.scale(radius))
            AttackRole.LEFT, AttackRole.RIGHT -> focus.subtract(forward.scale(radius * cos(angle))).add(side.scale(sign * radius * sin(angle)))
            AttackRole.RESERVE -> origin.subtract(forward.scale(32.0)).add(side.scale(force.center.subtract(origin).dot(side).coerceIn(-16.0, 16.0)))
        }
        val facing = focus.subtract(goal).multiply(1.0, 0.0, 1.0).normalize()
        val localSide = Vec3(-facing.z, 0.0, facing.x)
        val members = force.members.filter { it.role != NpcClass.MEDIC }.sortedWith(compareBy<TacticalMember> { it.position.dot(localSide) }.thenBy { it.id })
        val slots = members.mapIndexed { index, member -> member.id to SquadFormation.lineOffset(index, members.size).x }.toMap()
        val route = mutableListOf(force.center)
        if (role == AttackRole.LEFT || role == AttackRole.RIGHT) {
            // First open the lateral angle at the starting depth; don't cross the central gun line.
            val lateral = maxOf(kotlin.math.abs(goal.subtract(origin).dot(side)), halfWidth(central) + width + 8.0)
            route += force.center.add(side.scale(sign * lateral - force.center.subtract(origin).dot(side)))
        }
        route += goal
        return AttackLane(force.id, force.stamp, role, AttackCorridor(route.toList(), width + 6.0), slots, route)
    }

    private fun halfWidth(force: AttackForce) = (force.members.count { it.role != NpcClass.MEDIC } - 1).coerceAtLeast(0) * SquadFormation.COMBAT_SPACING / 2.0
}

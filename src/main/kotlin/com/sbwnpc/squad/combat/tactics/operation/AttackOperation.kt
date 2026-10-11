package com.sbwnpc.squad.combat.tactics.operation

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One local battle, with stable roles. World adapters must revoke changed orders before reading it. */
class AttackOperation(
    val id: Long,
    val focus: Vec3,
    forces: List<AttackForce>,
    started: Long,
    private val strategy: AttackDistribution = CenterAndWings,
    private val events: AttackOperationEvents = AttackOperationEvents.NONE,
) {
    val lanes = strategy.distribute(forces, focus).associateByTo(LinkedHashMap()) { it.squad }
    var state: AttackOperationState = DeployAttack()
        private set
    var phaseSince = started
        private set
    private var nextAdvance = started
    private var advancingLane = 0

    init {
        lanes.values.forEach {
            it.progressAt = started
            emit(started, "assigned", it, "initial_distribution", it.waypoint)
        }
    }

    fun tick(forces: List<AttackForce>, now: Long) {
        val present = forces.associateBy { it.id }
        for (lane in lanes.values.toList()) {
            val force = present[lane.squad]
            if (force == null || force.stamp != lane.stamp || force.fighters.isEmpty()) {
                lanes.remove(lane.squad)
                emit(now, "released", lane, "order_or_force_changed")
            } else {
                lane.reconcileMembers(force, focus.subtract(lane.sector).multiply(1.0, 0.0, 1.0).normalize())
            }
        }
        if (lanes.size < 2) {
            transition(FinishedAttack(), now, "insufficient_participants")
            return
        }
        state.tick(this, present, now)
    }

    fun assignment(squad: UUID, forces: List<AttackForce>): AttackAssignment? {
        if (state.stage == AttackStage.COMPLETED) return null
        val lane = lanes[squad] ?: return null
        val externalCover = forces.any { it.id != squad && it.id in lanes && it.covering >= 2 }
        return AttackAssignment(id, lane.role, state.stage, lane.revision, lane.waypoint, lane.sector, focus.subtract(lane.sector).multiply(1.0, 0.0, 1.0).normalize(), lane.corridor, lane.deployed, lane.blocked, externalCover, lane.slots.toMap())
    }

    /** Late arrivals fill a free wing, then reserve. Active survivors keep their current roles. */
    internal fun join(force: AttackForce, forces: List<AttackForce>, now: Long) {
        if (force.id in lanes || state.stage == AttackStage.COMPLETED) return
        val centralLane = lanes.values.firstOrNull { it.role == AttackRole.CENTER } ?: return
        val central = forces.firstOrNull { it.id == centralLane.squad }?.copy(center = centralLane.route.first()) ?: return
        val occupied = lanes.values.map { it.role }.toSet()
        val lane = strategy.reinforce(force, central, occupied, focus)
        lane.progressAt = now
        lanes[force.id] = lane
        emit(now, "assigned", lane, "reinforcement_joined", lane.waypoint)
        if (lane.role != AttackRole.RESERVE) transition(DeployAttack(), now, "new_wing_joined")
    }

    internal fun deploy(forces: Map<UUID, AttackForce>, now: Long) {
        for (lane in lanes.values) {
            if (lane.deployed || lane.blocked || lane.role == AttackRole.RESERVE) continue
            val force = forces[lane.squad] ?: continue
            val facing = focus.subtract(lane.sector).multiply(1.0, 0.0, 1.0).normalize()
            val side = Vec3(-facing.z, 0.0, facing.x)
            val remaining = force.fighters.map { member ->
                member.position.subtract(lane.waypoint.add(side.scale(lane.slots[member.id] ?: 0.0))).horizontalDistance()
            }.average()
            if (remaining < lane.closest - 2.0) {
                lane.closest = remaining
                lane.progressAt = now
            }
            val arrived = force.fighters.filter { member ->
                val point = lane.waypoint.add(side.scale(lane.slots[member.id] ?: 0.0))
                member.position.subtract(point).horizontalDistance() <= 16.0
            }
            // Medics, guards and a depleted wing make the raw squad median drift along
            // a deployed line. Measure the reference frame of actual reached slots.
            val aligned = arrived.fold(Vec3.ZERO) { sum, member ->
                sum.add(member.position.subtract(side.scale(lane.slots[member.id] ?: 0.0)))
            }.scale(1.0 / arrived.size.coerceAtLeast(1))
            val distance = aligned.subtract(lane.waypoint).horizontalDistance()
            val legLength = lane.waypoint.subtract(lane.route[(lane.next - 1).coerceAtLeast(0)]).horizontalDistance()
            val arrivalTolerance = minOf(10.0, maxOf(4.0, legLength / 2.0))
            if (distance <= arrivalTolerance && arrived.size * 4 >= force.fighters.size * 3) {
                lane.next++
                lane.revision++
                lane.closest = Double.POSITIVE_INFINITY
                lane.progressAt = now
                emit(now, if (lane.deployed) "sector_reached" else "waypoint_reached", lane, "physical_squad_progress", lane.waypoint)
            } else if (now - lane.progressAt >= 400 || (force.failedRoutes >= maxOf(2, force.fighters.size / 2) && now - lane.progressAt >= 160)) {
                // An inaccessible flank is a failed maneuver, not a successful deployment.
                lane.blocked = true
                lane.route += force.center
                lane.next = lane.route.size
                lane.revision++
                emit(now, "route_blocked", lane, "no_physical_progress_hold_locally", force.center)
            }
        }
        if (lanes.values.all { it.deployed || it.blocked || it.role == AttackRole.RESERVE }) transition(EngageAttack(), now, "deployment_finished")
    }

    internal fun engage(forces: Map<UUID, AttackForce>, now: Long) {
        val active = lanes.values.filter { !it.blocked && it.role != AttackRole.RESERVE }
        if (active.isNotEmpty() && active.all { forces[it.squad]?.covering?.let { count -> count >= 1 } == true } && now - phaseSince >= 40) {
            transition(AdvanceAttack(), now, "actual_fire_from_deployed_sectors")
        }
    }

    internal fun advance(forces: Map<UUID, AttackForce>, now: Long) {
        if (now < nextAdvance) return
        nextAdvance = now + 60
        val active = lanes.values.filter { !it.blocked && it.role != AttackRole.RESERVE }
        if (active.isEmpty()) return
        val lane = active[advancingLane++ % active.size]
        val covered = forces.values.any { it.id != lane.squad && it.id in lanes && it.covering >= 2 }
        val force = forces[lane.squad] ?: return
        if (!covered || force.fighters.any { it.suppressed }) return
        val delta = focus.subtract(lane.sector).multiply(1.0, 0.0, 1.0)
        if (delta.length() <= 72.0) return
        val goal = lane.sector.add(delta.normalize().scale(minOf(8.0, delta.length() - 72.0)))
        lane.route += goal
        lane.corridor = AttackCorridor(lane.route.toList(), lane.corridor.halfWidth)
        lane.next = lane.route.lastIndex
        lane.revision++
        lane.closest = Double.POSITIVE_INFINITY
        lane.progressAt = now
        emit(now, "advance_ordered", lane, "adjacent_sector_actual_cover", goal)
        transition(DeployAttack(), now, "next_protected_bound")
    }

    internal fun transition(next: AttackOperationState, now: Long, reason: String) {
        if (state.stage == next.stage) return
        state = next
        phaseSince = now
        events.emit(AttackOperationEvent(id, now, "stage_${next.stage.name.lowercase()}", null, null, reason))
    }

    private fun emit(now: Long, action: String, lane: AttackLane, reason: String, point: Vec3? = null) = events.emit(AttackOperationEvent(id, now, action, lane.squad, lane.role, reason, point))
}

/** Operation states own phase transitions; allocation remains a replaceable strategy. */
sealed interface AttackOperationState {
    val stage: AttackStage
    fun tick(operation: AttackOperation, forces: Map<UUID, AttackForce>, now: Long)
}
class DeployAttack : AttackOperationState {
    override val stage = AttackStage.DEPLOYING
    override fun tick(operation: AttackOperation, forces: Map<UUID, AttackForce>, now: Long) = operation.deploy(forces, now)
}
class EngageAttack : AttackOperationState {
    override val stage = AttackStage.ENGAGING
    override fun tick(operation: AttackOperation, forces: Map<UUID, AttackForce>, now: Long) = operation.engage(forces, now)
}
class AdvanceAttack : AttackOperationState {
    override val stage = AttackStage.ADVANCING
    override fun tick(operation: AttackOperation, forces: Map<UUID, AttackForce>, now: Long) = operation.advance(forces, now)
}
class FinishedAttack : AttackOperationState {
    override val stage = AttackStage.COMPLETED
    override fun tick(operation: AttackOperation, forces: Map<UUID, AttackForce>, now: Long) = Unit
}

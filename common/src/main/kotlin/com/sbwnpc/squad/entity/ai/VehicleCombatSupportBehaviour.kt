package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TankWeaponSelection
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

class VehicleCombatSupportBehaviour : ExtendedBehaviour<NpcEntity>() {
    private enum class Phase { APPROACHING, FIRING }

    private var phase = Phase.APPROACHING
    private var vehicleId: UUID? = null
    private var targetId: UUID? = null

    /** Last tick the target was in sight — see [engagementTarget]. */
    private var sawTargetTick = 0

    /** Last tick the gunner had anything to shoot at — see [lingering]. */
    private var engagedTick = 0

    // The keep-running check and firing tick ask about the same line; raycast it once per tick.
    private var sightTick = Int.MIN_VALUE
    private var sightTarget: UUID? = null
    private var sightClear = false

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    /** Out of a vehicle having lost the target: not straight back into the same one. */
    private var nextBoardTick = 0

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        entity.tickCount >= nextBoardTick && entity.seatFreeFor(this) && eligible(entity) &&
            threat(entity)?.let { hasFiringSightline(entity, it) } == true &&
            findSupportVehicle(entity, level) != null

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
            ?: return false
        if (!Ports.vehicles.isOperational(vehicle)) return false
        if (!orderAllowsSupport(entity)) {
            return entity.vehicle === vehicle && vehicle.deltaMovement.horizontalDistanceSqr() > DISMOUNT_SPEED_SQR
        }
        if (engagementTarget(entity) == null) return lingering(entity, vehicle)
        // A vehicle's hull is the gunner's cover. Once claimed, reach and keep it despite sensor
        // line-of-sight gaps or suppression; these are not reasons to abandon an armoured seat.
        if (entity.vehicle === vehicle) return true
        return entity.vehicle == null && VehicleTransportClaims.vehicleOf(entity.uuid) == vehicle.uuid &&
            !Ports.vehicles.isLocked(vehicle) && !hasPlayerAboard(vehicle)
    }

    override fun start(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val target = threat(entity) ?: return
        val vehicle = findSupportVehicle(entity, level) ?: return
        if (!entity.claimSeat(this)) return
        if (!VehicleTransportClaims.claimPassenger(vehicle.uuid, entity.uuid, Ports.vehicles.seatCount(vehicle))) {
            entity.release(NpcActivity.SEAT, this)
            return
        }
        vehicleId = vehicle.uuid
        targetId = target.uuid
        sawTargetTick = entity.tickCount
        engagedTick = entity.tickCount
        phase = Phase.APPROACHING
        entity.claim(NpcActivity.VEHICLE, this)
        DebugFlags.log(
            LogGroup.VEHICLE,
            "{} claimed combat support vehicle {} for target {}",
            entity.uuid,
            vehicle.uuid,
            target.uuid,
        )
    }

    override fun stop(entity: NpcEntity) {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
        traceExit(entity, vehicle, "stop")
        if (vehicle != null && entity.vehicle === vehicle) {
            Ports.vehicles.aimAt(vehicle, entity, null)
            entity.stopRiding()
        }
        VehicleTransportClaims.release(entity.uuid)
        entity.release(NpcActivity.SEAT, this)
        entity.release(NpcActivity.VEHICLE, this)
        entity.navigation.stop()
        vehicleId = null
        targetId = null
        phase = Phase.APPROACHING
        nextBoardTick = entity.tickCount + REBOARD_COOLDOWN_TICKS
    }

    override fun tick(entity: NpcEntity) {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
            ?: run {
                abort(entity, "vehicle gone")
                return
            }
        if (!orderAllowsSupport(entity)) {
            Ports.vehicles.aimAt(vehicle, entity, null)
            Ports.vehicles.release(vehicle)
            Ports.vehicles.cutPower(vehicle)
            entity.navigation.stop()
            return
        }
        val target = engagementTarget(entity)
        if (target == null) {
            if (lingering(entity, vehicle)) {
                // Off the dead target: SBW's gun keeps working the last UUID it was given.
                Ports.vehicles.aimAt(vehicle, entity, null)
                entity.navigation.stop()
                return
            }
            abort(entity, "no target")
            return
        }

        if (entity.vehicle === vehicle) {
            phase = Phase.FIRING
        }
        when (phase) {
            Phase.APPROACHING -> {
                if (vehicle.boundingBox.distanceToSqr(entity.position()) > BOARD_DISTANCE_SQR) {
                    val hull = vehicle.boundingBox
                    entity.navigateTo(
                        entity.x.coerceIn(hull.minX, hull.maxX),
                        entity.y.coerceIn(hull.minY, hull.maxY),
                        entity.z.coerceIn(hull.minZ, hull.maxZ),
                        BOARD_SPEED,
                    )
                    return
                }
                if (!entity.startRiding(vehicle, false) || !Ports.vehicles.seatHasAmmo(vehicle, entity)) {
                    if (entity.vehicle === vehicle) entity.stopRiding()
                    abort(entity, "could not board or seat empty")
                    return
                }
                entity.navigation.stop()
                phase = Phase.FIRING
                engagedTick = entity.tickCount
                DebugFlags.log(
                    LogGroup.VEHICLE,
                    "{} boarded combat support vehicle {} in seat {} for target {}",
                    entity.uuid,
                    vehicle.uuid,
                    Ports.vehicles.seatOf(vehicle, entity),
                    target.uuid,
                )
            }

            Phase.FIRING -> {
                if (!Ports.vehicles.seatHasAmmo(vehicle, entity)) {
                    abort(entity, "seat out of ammo")
                    return
                }
                if (entity.target !== target) {
                    BrainUtils.setTargetOfEntity(entity, target)
                }
                TankWeaponSelection.update(entity, target)
                Ports.vehicles.aimAt(vehicle, entity, target.takeIf { hasFiringSightline(entity, it) })
                Ports.vehicles.release(vehicle)
                Ports.vehicles.cutPower(vehicle)
                entity.navigation.stop()
            }
        }
    }

    private fun eligible(entity: NpcEntity): Boolean =
        orderAllowsSupport(entity) && !entity.vehicleTransport && !entity.operatingDrone && !entity.antiDroneEngaged && !entity.diggedIn && !entity.isSuppressed() &&
            entity.npcClass != NpcClass.MORTAR &&
            threat(entity) != null

    /** A temporary weapon station cannot hold up an explicit march or the squad's withdrawal. */
    private fun orderAllowsSupport(entity: NpcEntity): Boolean {
        val order = entity.currentSquad()?.order
        return order != SquadOrder.MOVE && order != SquadOrder.RETREAT
    }

    // The hostile-vehicle scan (an entity query + raycasts) used to run from this behaviour's
    // start check EVERY tick for EVERY non-mortar NPC on the map, whether or not anything armoured
    // was anywhere near — by far the most expensive per-tick idle work in the brain. Cached for a
    // few ticks; a cached hit is still re-validated (alive, hostile, still mounted) on every read.
    private var threatScanTick = Int.MIN_VALUE / 2 // not MIN_VALUE: `tickCount - MIN_VALUE` overflows
    private var threatCache: LivingEntity? = null
    private var nextVehicleSearchTick = 0

    private fun threat(entity: NpcEntity): LivingEntity? {
        if (entity.tickCount - threatScanTick < THREAT_RESCAN_TICKS) {
            val cached = threatCache
            if (cached == null) return fallbackThreat(entity)
            if (cached.isAlive && Ports.vehicles.isVehicle(cached.vehicle) && SquadTeams.isHostile(entity, cached)) return cached
        }
        threatScanTick = entity.tickCount
        val level = entity.level() as? ServerLevel
        threatCache = level?.let { VehicleTargeting.closestVisibleHostileVehicleOccupant(entity, it, TARGET_SEARCH_RADIUS) { target -> VehicleTargeting.weaponPermits(entity, target) } }
        return threatCache ?: fallbackThreat(entity)
    }

    private fun fallbackThreat(entity: NpcEntity): LivingEntity? = entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) }

    private fun abort(entity: NpcEntity, why: String) {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
        traceExit(entity, vehicle, "abort: $why")
        if (vehicle != null && entity.vehicle === vehicle) {
            Ports.vehicles.aimAt(vehicle, entity, null)
            entity.stopRiding()
        }
        VehicleTransportClaims.release(entity.uuid)
        vehicleId = null
        targetId = null
        entity.release(NpcActivity.SEAT, this)
        entity.release(NpcActivity.VEHICLE, this)
        entity.navigation.stop()
    }

    private fun traceExit(entity: NpcEntity, vehicle: Entity?, how: String) {
        DebugFlags.log(
            LogGroup.VEHICLE,
            "{} leaving combat support ({}): seated={} ticksSinceTarget={} ammo={} phase={}",
            entity.uuid,
            how,
            vehicle != null && entity.vehicle === vehicle,
            entity.tickCount - engagedTick,
            vehicle?.let { Ports.vehicles.seatHasAmmo(it, entity) },
            phase,
        )
    }

    /**
     * Seated with nothing left to shoot: stays at the gun for [LINGER_TICKS] after the last target
     * in case another turns up. It used to climb out the tick its target fell — two shots, out —
     * which looked absurd and left the gun empty just as the next enemy came round the corner.
     */
    private fun lingering(entity: NpcEntity, vehicle: Entity): Boolean =
        entity.vehicle === vehicle && entity.tickCount - engagedTick < LINGER_TICKS &&
            Ports.vehicles.seatHasAmmo(vehicle, entity)

    /** Keeps a pre-boarding target through sensor gaps caused by the vehicle's own collision hull —
     *  but only gaps: once it has been out of sight for [LOST_SIGHT_TICKS] the gunner lets it go and
     *  gets out, rather than sitting alone in the vehicle for as long as that enemy lives. */
    private fun engagementTarget(entity: NpcEntity): LivingEntity? {
        val level = entity.level() as? ServerLevel ?: return threat(entity)
        val held = targetId?.let { level.getEntity(it) as? LivingEntity }?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) }
        if (held != null) {
            val inSight = hasFiringSightline(entity, held)
            if (inSight) sawTargetTick = entity.tickCount
            val shootable = if (inSight) null else shootableSensorPick(entity, held)
            if (shootable != null) return takeTarget(entity, shootable)
            if (entity.tickCount - sawTargetTick < LOST_SIGHT_TICKS) {
                engagedTick = entity.tickCount
                return held
            }
        }
        targetId = null
        return threat(entity)?.takeIf { hasFiringSightline(entity, it) }?.let { takeTarget(entity, it) }
    }

    /**
     * The sensor's target while [held] is out of sight, when the gun can hit it. The hidden one used to
     * be put back every tick, and the gun sat silent until the gunner climbed out (in-game 24.10).
     */
    private fun shootableSensorPick(entity: NpcEntity, held: LivingEntity): LivingEntity? = entity.target?.takeIf { it !== held && it.isAlive && SquadTeams.isHostile(entity, it) && hasFiringSightline(entity, it) }

    private fun takeTarget(entity: NpcEntity, target: LivingEntity): LivingEntity {
        DebugFlags.log(
            LogGroup.VEHICLE,
            "{} at the gun takes a new target {} ({} ticks after the last)",
            entity.uuid,
            target.uuid,
            entity.tickCount - engagedTick,
        )
        targetId = target.uuid
        sawTargetTick = entity.tickCount
        engagedTick = entity.tickCount
        return target
    }

    /** A head peeking above cover cannot keep a gun aimed at the hidden body indefinitely. */
    private fun hasFiringSightline(entity: NpcEntity, target: LivingEntity): Boolean {
        if (sightTick == entity.tickCount && sightTarget == target.uuid) return sightClear
        val level = entity.level() as ServerLevel
        // SBW shoots the hull when its occupant is the target, not the hidden crewman's body.
        val body = target.vehicle?.takeIf(Ports.vehicles::isVehicle) ?: target
        val aim = body.boundingBox.center
        sightTick = entity.tickCount
        sightTarget = target.uuid
        sightClear = entity.vehicle?.takeIf(Ports.vehicles::isVehicle)?.let { Ports.vehicles.canAimAt(it, entity, target) }
            ?: (DetectionSightline.visible(level, entity.eyePosition, aim, entity) && !Sightline.blocked(level, entity.eyePosition, aim, entity))
        return sightClear
    }

    private fun findSupportVehicle(entity: NpcEntity, level: ServerLevel): Entity? {
        // Only reached with a live threat; while there's simply no free armed vehicle around, don't
        // re-run the box query every tick — a "none" answer is good for a second.
        if (entity.tickCount < nextVehicleSearchTick) return null
        val target = threat(entity) ?: return null
        val box = AABB.ofSize(entity.position(), SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2)
        val found = Ports.vehicles.within(level, box)
            .asSequence()
            .filter { entity.distanceToSqr(it) <= SEARCH_RADIUS_SQR }
            .filter(::isSupportVehicle)
            .filter { vehicle -> Ports.vehicles.canAimAt(vehicle, entity, target, Ports.vehicles.seating(vehicle).indexOfFirst { it == null }) }
            .minByOrNull { entity.distanceToSqr(it) }
        if (found == null) nextVehicleSearchTick = entity.tickCount + VEHICLE_SEARCH_INTERVAL_TICKS
        return found
    }

    private fun isSupportVehicle(vehicle: Entity): Boolean {
        if (!Ports.vehicles.isOperational(vehicle) || Ports.vehicles.isLocked(vehicle) || vehicle.passengers.isNotEmpty()) return false
        if (hasPlayerAboard(vehicle) || VehicleTransportClaims.claimedSeats(vehicle.uuid) != 0) return false
        val seat = Ports.vehicles.seating(vehicle).indexOfFirst { it == null }
        return seat >= 0 && Ports.vehicles.seatReady(vehicle, seat)
    }

    private fun hasPlayerAboard(vehicle: Entity): Boolean = vehicle.passengers.any { it is Player }

    private companion object {
        const val SEARCH_RADIUS = 20.0
        const val SEARCH_RADIUS_SQR = SEARCH_RADIUS * SEARCH_RADIUS
        const val TARGET_SEARCH_RADIUS = 48.0
        const val THREAT_RESCAN_TICKS = 10
        const val VEHICLE_SEARCH_INTERVAL_TICKS = 20
        const val BOARD_DISTANCE_SQR = 2.5 * 2.5
        const val BOARD_SPEED = 1.0
        const val LOST_SIGHT_TICKS = 200
        const val REBOARD_COOLDOWN_TICKS = 200

        /** How long a gunner stays at the gun after its last target, looking for the next. */
        const val LINGER_TICKS = 100

        /** Wait for a moving support vehicle to stop before relinquishing its seat. */
        const val DISMOUNT_SPEED_SQR = 0.0004
    }
}

package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.TankWeaponSelection
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
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

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        eligible(entity) && findSupportVehicle(entity, level) != null

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
            ?: return false
        if (!Ports.vehicles.isOperational(vehicle) || engagementTarget(entity) == null) return false
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
        if (!VehicleTransportClaims.claimPassenger(vehicle.uuid, entity.uuid, Ports.vehicles.seatCount(vehicle))) return
        vehicleId = vehicle.uuid
        targetId = target.uuid
        phase = Phase.APPROACHING
        entity.vehicleTransport = true
        DebugFlags.log(
            "[vehicle-debug] {} claimed combat support vehicle {} for target {}",
            entity.uuid, vehicle.uuid, target.uuid
        )
    }

    override fun stop(entity: NpcEntity) {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
        if (vehicle != null && entity.vehicle === vehicle) {
            Ports.vehicles.aimAt(vehicle, entity, null)
            entity.stopRiding()
        }
        VehicleTransportClaims.release(entity.uuid)
        entity.vehicleTransport = false
        entity.navigation.stop()
        vehicleId = null
        targetId = null
        phase = Phase.APPROACHING
    }

    override fun tick(entity: NpcEntity) {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
            ?: run {
                abort(entity)
                return
            }
        val target = engagementTarget(entity)
        if (target == null) {
            abort(entity)
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
                        BOARD_SPEED
                    )
                    return
                }
                if (!entity.startRiding(vehicle, false) || !Ports.vehicles.seatHasAmmo(vehicle, entity)) {
                    if (entity.vehicle === vehicle) entity.stopRiding()
                    abort(entity)
                    return
                }
                entity.navigation.stop()
                phase = Phase.FIRING
                DebugFlags.log(
                    "[vehicle-debug] {} boarded combat support vehicle {} in seat {} for target {}",
                    entity.uuid, vehicle.uuid, Ports.vehicles.seatOf(vehicle, entity), target.uuid
                )
            }
            Phase.FIRING -> {
                if (!Ports.vehicles.seatHasAmmo(vehicle, entity)) {
                    abort(entity)
                    return
                }
                if (entity.target !== target) {
                    BrainUtils.setTargetOfEntity(entity, target)
                }
                TankWeaponSelection.update(entity, target)
                Ports.vehicles.aimAt(vehicle, entity, target)
                Ports.vehicles.release(vehicle)
                Ports.vehicles.cutPower(vehicle)
                entity.navigation.stop()
            }
        }
    }

    private fun eligible(entity: NpcEntity): Boolean =
        !entity.vehicleTransport && !entity.operatingDrone && !entity.antiDroneEngaged && !entity.diggedIn && !entity.isSuppressed() &&
            entity.npcClass != NpcClass.MORTAR_OPERATOR && entity.npcClass != NpcClass.MORTAR_LOADER &&
            threat(entity) != null

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
        threatCache = level?.let { VehicleTargeting.closestVisibleHostileVehicleOccupant(entity, it, TARGET_SEARCH_RADIUS) }
        return threatCache ?: fallbackThreat(entity)
    }

    private fun fallbackThreat(entity: NpcEntity): LivingEntity? =
        entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) }

    private fun abort(entity: NpcEntity) {
        val vehicle = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
        if (vehicle != null && entity.vehicle === vehicle) {
            Ports.vehicles.aimAt(vehicle, entity, null)
            entity.stopRiding()
        }
        VehicleTransportClaims.release(entity.uuid)
        vehicleId = null
        targetId = null
        entity.vehicleTransport = false
        entity.navigation.stop()
    }

    /** Keeps a pre-boarding target through sensor gaps caused by the vehicle's own collision hull. */
    private fun engagementTarget(entity: NpcEntity): LivingEntity? {
        val level = entity.level() as? ServerLevel ?: return threat(entity)
        targetId?.let { id ->
            val target = level.getEntity(id) as? LivingEntity
            if (target != null && target.isAlive && SquadTeams.isHostile(entity, target)) return target
        }
        return threat(entity)?.also { targetId = it.uuid }
    }

    private fun findSupportVehicle(entity: NpcEntity, level: ServerLevel): Entity? {
        // Only reached with a live threat; while there's simply no free armed vehicle around, don't
        // re-run the box query every tick — a "none" answer is good for a second.
        if (entity.tickCount < nextVehicleSearchTick) return null
        val box = AABB.ofSize(entity.position(), SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2)
        val found = Ports.vehicles.within(level, box)
            .asSequence()
            .filter { entity.distanceToSqr(it) <= SEARCH_RADIUS_SQR }
            .filter(::isSupportVehicle)
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
    }
}

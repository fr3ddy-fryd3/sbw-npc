package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import java.util.UUID

/**
 * A man of a FOLLOW squad gets into the vehicle his owner sits in, if he reaches it before it
 * leaves, and gets out once the owner does (#116).
 *
 * [VehicleTransportBehaviour] only rides towards a far objective, and a FOLLOW squad's objective
 * is always next to the player, so it never boarded. While aboard the man holds
 * [NpcActivity.SEAT], so the squad transport and the helicopter ride leave him in his seat, and
 * [NpcActivity.VEHICLE], which keeps the squad's movement and shooting off him, as for any rider.
 */
class FollowRideBehaviour : ExtendedBehaviour<NpcEntity>() {
    private var vehicleId: UUID? = null

    /** The tick the owner was first seen out of the vehicle, or -1 while he is in it. */
    private var ownerOutTick = -1

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        entity.vehicle == null && entity.seatFreeFor(this) && !entity.vehicleTransport && !entity.operatingDrone && !entity.antiDroneEngaged && !entity.diggedIn &&
            ownerVehicle(level, entity)?.let { boardable(entity, it) } == true

    override fun start(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val vehicle = ownerVehicle(level, entity) ?: return
        if (!entity.claimSeat(this)) return
        if (!VehicleTransportClaims.claimPassenger(vehicle.uuid, entity.uuid, Ports.vehicles.seatCount(vehicle), vehicle.passengers.map { it.uuid })) {
            entity.release(NpcActivity.SEAT, this)
            return
        }
        vehicleId = vehicle.uuid
        ownerOutTick = -1
        entity.claim(NpcActivity.VEHICLE, this)
        DebugFlags.log(LogGroup.VEHICLE, "{} follows its owner into {}", entity.uuid, vehicle.uuid)
    }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        val vehicle = vehicle(entity) ?: return false
        if (!Ports.vehicles.isOperational(vehicle) || entity.currentSquad()?.order != SquadOrder.FOLLOW) return false
        if (entity.vehicle === vehicle) return ownerOutTick < 0 || entity.tickCount - ownerOutTick < DISMOUNT_DELAY_TICKS
        // Not aboard yet: only while the owner is still in it and it has not left him behind.
        return ownerVehicle(entity.level() as ServerLevel, entity) === vehicle && entity.distanceTo(vehicle) <= GIVE_UP_DISTANCE
    }

    override fun tick(entity: NpcEntity) {
        val vehicle = vehicle(entity) ?: return
        if (entity.vehicle === vehicle) {
            entity.navigation.stop()
            val ownerAboard = ownerVehicle(entity.level() as ServerLevel, entity) === vehicle
            ownerOutTick = when {
                ownerAboard -> -1
                ownerOutTick < 0 -> entity.tickCount
                else -> ownerOutTick
            }
            return
        }
        if (vehicle.boundingBox.distanceToSqr(entity.position()) > BOARD_DISTANCE_SQR) {
            val hull = vehicle.boundingBox
            entity.navigateTo(entity.x.coerceIn(hull.minX, hull.maxX), entity.y.coerceIn(hull.minY, hull.maxY), entity.z.coerceIn(hull.minZ, hull.maxZ), BOARD_SPEED)
            return
        }
        if (entity.startRiding(vehicle, false)) {
            entity.navigation.stop()
            DebugFlags.log(LogGroup.VEHICLE, "{} rides with its owner in {} seat {}", entity.uuid, vehicle.uuid, Ports.vehicles.seatOf(vehicle, entity))
        } else {
            DebugFlags.log(LogGroup.VEHICLE, "{} could not get into its owner's {}", entity.uuid, vehicle.uuid)
            vehicleId = null
        }
    }

    override fun stop(entity: NpcEntity) {
        val vehicle = vehicle(entity)
        if (vehicle != null && entity.vehicle === vehicle) {
            // A gun left with a target keeps firing at it with nobody aboard.
            if (Ports.vehicles.hasWeaponAt(vehicle, entity)) Ports.vehicles.aimAt(vehicle, entity, null)
            entity.stopRiding()
            DebugFlags.log(LogGroup.VEHICLE, "{} gets out of {} after its owner", entity.uuid, vehicle.uuid)
        }
        VehicleTransportClaims.release(entity.uuid)
        entity.release(NpcActivity.SEAT, this)
        entity.release(NpcActivity.VEHICLE, this)
        entity.navigation.stop()
        vehicleId = null
        ownerOutTick = -1
    }

    private fun vehicle(entity: NpcEntity): Entity? = vehicleId?.let { (entity.level() as? ServerLevel)?.getEntity(it) }?.takeIf(Ports.vehicles::isVehicle)

    /** The vehicle the owner of [entity]'s FOLLOW squad sits in, if any. */
    private fun ownerVehicle(level: ServerLevel, entity: NpcEntity): Entity? {
        val squad = entity.currentSquad()?.takeIf { it.order == SquadOrder.FOLLOW } ?: return null
        val owner = level.getPlayerByUUID(squad.owner) ?: return null
        return owner.vehicle?.takeIf(Ports.vehicles::isVehicle)
    }

    private fun boardable(entity: NpcEntity, vehicle: Entity): Boolean =
        entity.distanceTo(vehicle) <= BOARD_RANGE && Ports.vehicles.isOperational(vehicle) && !Ports.vehicles.isLocked(vehicle) &&
            VehicleTransportClaims.occupiedOrClaimedSeats(vehicle.uuid, vehicle.passengers.map { it.uuid }) < Ports.vehicles.seatCount(vehicle)

    private companion object {
        /** How far from the owner's vehicle a man still tries to get in. */
        const val BOARD_RANGE = 24.0

        /** A vehicle that pulled this far away left the man behind; he follows on foot. */
        const val GIVE_UP_DISTANCE = 32.0

        const val BOARD_DISTANCE_SQR = 2.5 * 2.5
        const val BOARD_SPEED = 1.3

        /** The owner out of the vehicle this long: his men get out too. A seat swap is not getting out. */
        const val DISMOUNT_DELAY_TICKS = 10
    }
}

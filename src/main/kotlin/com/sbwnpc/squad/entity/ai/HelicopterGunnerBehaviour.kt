package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.VehicleCannonAmmo
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import java.util.UUID

/**
 * Works the gunship's turret. SBW already flies the turret onto a target by itself once it has an
 * auto-aim UUID (`AutoAimableEntity`), which is the same machinery tank and IFV gunners use here —
 * so this only has to keep that UUID pointed at the right thing and keep the right round loaded.
 *
 * Nothing here touches the aircraft's flight: [HelicopterPilotBehaviour] parks it at a standoff
 * hover facing the target, which is what puts the target inside the turret's declared traverse.
 */
class HelicopterGunnerBehaviour : ExtendedBehaviour<NpcEntity>() {
    private var nextSearchTick = 0
    private var seatTarget: UUID? = null

    private companion object {
        const val CANNON_WEAPON = 0
        const val SEARCH_RANGE = 40.0
        const val SEARCH_INTERVAL_TICKS = 40
        const val BOARD_DISTANCE_SQR = 2.5 * 2.5
        const val BOARD_SPEED = 1.0
    }

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        entity.npcClass == NpcClass.HELICOPTER_GUNNER &&
            (turret(entity) != null || openTurretSeat(entity, level) != null)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        if (entity.npcClass != NpcClass.HELICOPTER_GUNNER) return false
        val level = entity.level() as? ServerLevel ?: return false
        return turret(entity) != null || openTurretSeat(entity, level) != null
    }

    override fun start(entity: NpcEntity) {
        entity.vehicleTransport = true
    }

    override fun stop(entity: NpcEntity) {
        entity.vehicle?.let { Ports.vehicles.aimAt(it, entity, null) }
        entity.vehicleTransport = false
    }

    override fun tick(entity: NpcEntity) {
        val gunship = turret(entity) ?: run {
            boardVacantTurret(entity)
            return
        }
        entity.vehicleTransport = true
        entity.navigation.stop()
        val target = entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) }
        if (target == null) {
            Ports.vehicles.aimAt(gunship, entity, null)
            return
        }
        VehicleCannonAmmo.select(gunship, Helicopters.GUNNER_SEAT, CANNON_WEAPON, target)
        Ports.vehicles.aimAt(gunship, entity, target)
    }

    /**
     * A friendly gunship sitting on the ground with nobody on the gun. Losing the gunner leaves the
     * airframe flying around as a very expensive spotter, so a replacement gunner spawned nearby
     * takes the seat. Only ever a landed one — there is no way to climb into a helicopter in the
     * air, and the pilot shuts down on the pad, which is exactly when this can happen.
     */
    private fun openTurretSeat(entity: NpcEntity, level: ServerLevel): Entity? {
        if (entity.vehicle != null) return null
        if (entity.tickCount < nextSearchTick) return null
        nextSearchTick = entity.tickCount + SEARCH_INTERVAL_TICKS
        val box = AABB.ofSize(entity.position(), SEARCH_RANGE * 2, SEARCH_RANGE * 2, SEARCH_RANGE * 2)
        return Ports.vehicles.within(level, box)
            .asSequence()
            .filter { Helicopters.hasTurret(it) && Ports.vehicles.isOperational(it) && !Ports.vehicles.isLocked(it) }
            .filter { it.onGround() }
            .filter { SquadTeams.factionOf(it) == SquadTeams.factionOf(entity) }
            .filter { Ports.vehicles.seating(it).getOrNull(Helicopters.GUNNER_SEAT) == null }
            .minByOrNull { entity.distanceToSqr(it) }
    }

    private fun boardVacantTurret(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val gunship = seatTarget?.let { level.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
            ?: openTurretSeat(entity, level)?.also { seatTarget = it.uuid }
            ?: return
        if (Ports.vehicles.seating(gunship).getOrNull(Helicopters.GUNNER_SEAT) != null || !gunship.onGround()) {
            seatTarget = null
            return
        }
        val hull = gunship.boundingBox
        if (hull.distanceToSqr(entity.position()) > BOARD_DISTANCE_SQR) {
            entity.navigateTo(
                entity.x.coerceIn(hull.minX, hull.maxX),
                entity.y.coerceIn(hull.minY, hull.maxY),
                entity.z.coerceIn(hull.minZ, hull.maxZ),
                BOARD_SPEED
            )
            return
        }
        entity.navigation.stop()
        if (entity.startRiding(gunship, false)) {
            entity.assignedVehicleId = gunship.uuid
            seatTarget = null
            DebugFlags.log("[heli-debug] {} took over the turret on {}", entity.uuid, gunship.uuid)
        }
    }

    private fun turret(entity: NpcEntity): Entity? {
        val vehicle = entity.vehicle ?: return null
        if (!Helicopters.hasTurret(vehicle) || !Ports.vehicles.isOperational(vehicle)) return null
        if (Ports.vehicles.seatOf(vehicle, entity) != Helicopters.GUNNER_SEAT) return null
        return vehicle
    }
}

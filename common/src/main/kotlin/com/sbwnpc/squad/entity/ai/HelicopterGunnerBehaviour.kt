package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
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
 * Walks a [NpcClass.HELICOPTER_GUNNER] on foot to a landed friendly gunship whose turret has nobody
 * on it, and seats him there. Getting into a seat is seating, as [VehicleCrewBehaviour]'s is; once
 * aboard, his aircraft's plan works the gun (#261).
 */
class HelicopterGunnerBehaviour : ExtendedBehaviour<NpcEntity>() {
    private var nextSearchTick = 0
    private var seatTarget: UUID? = null

    private companion object {
        const val SEARCH_RANGE = 40.0
        const val SEARCH_INTERVAL_TICKS = 40
        const val BOARD_DISTANCE_SQR = 2.5 * 2.5
        const val BOARD_SPEED = 1.0
    }

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    // He holds the seat himself only while walking to it; aboard, his crew contract holds it.
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = boarding(entity, level)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean = (entity.level() as? ServerLevel)?.let { boarding(entity, it) } == true

    private fun boarding(entity: NpcEntity, level: ServerLevel) = entity.npcClass == NpcClass.HELICOPTER_GUNNER && entity.vehicle == null && entity.freeFor(NpcActivity.SEAT, this) && openTurretSeat(entity, level) != null

    override fun start(entity: NpcEntity) {
        entity.claimAlone(NpcActivity.SEAT, this)
        entity.claim(NpcActivity.VEHICLE, this)
    }

    override fun stop(entity: NpcEntity) {
        entity.release(NpcActivity.SEAT, this)
        entity.release(NpcActivity.VEHICLE, this)
    }

    override fun tick(entity: NpcEntity) = boardVacantTurret(entity)

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
            .filter { gunship -> !SeatHold.refuses(gunship, entity, Ports.vehicles.seating(gunship).count { it != null }) }
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
                BOARD_SPEED,
            )
            return
        }
        entity.navigation.stop()
        if (entity.startRiding(gunship, false)) {
            entity.assignedVehicleId = gunship.uuid
            seatTarget = null
            DebugFlags.log(LogGroup.HELI, "{} took over the turret on {}", entity.uuid, gunship.uuid)
        }
    }
}

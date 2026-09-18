package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/** Keeps a spawned vehicle crewman with its assigned vehicle until that vehicle is wrecked. */
class VehicleCrewBehaviour : ExtendedBehaviour<NpcEntity>() {
    private var nextRecoverySearchTick = 0
    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun assignedVehicle(entity: NpcEntity): VehicleEntity? {
        val level = entity.level() as? ServerLevel ?: return null
        if (entity.assignedVehicleId == null && entity.npcClass == NpcClass.TANK_CREW) {
            if (entity.tickCount < nextRecoverySearchTick) return null
            nextRecoverySearchTick = entity.tickCount + 20
            // Repairs crews spawned by the earlier T-90 preset, which seated them but failed to
            // persist the assignment. Only a same-faction T-90 can become their vehicle.
            val current = entity.vehicle as? VehicleEntity
            val vehicle = current?.takeIf(::isT90)
                ?: level.getEntitiesOfClass(
                    VehicleEntity::class.java,
                    AABB.ofSize(entity.position(), RECOVERY_RANGE * 2, RECOVERY_RANGE * 2, RECOVERY_RANGE * 2)
                ).filter(::isT90)
                    .filter { SquadTeams.factionOf(it) == SquadTeams.factionOf(entity) }
                    .minByOrNull { entity.distanceToSqr(it) }
            entity.assignedVehicleId = vehicle?.uuid
        }
        val id = entity.assignedVehicleId ?: return null
        val vehicle = level.getEntity(id) as? VehicleEntity
        if (vehicle == null || !vehicle.isAlive || vehicle.isWreck) {
            entity.assignedVehicleId = null
            return null
        }
        return vehicle
    }

    private fun isT90(vehicle: VehicleEntity): Boolean = vehicle.type == ModEntities.T_90A.get()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = assignedVehicle(entity) != null
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = assignedVehicle(entity) != null

    override fun start(entity: NpcEntity) {
        entity.vehicleTransport = true
    }

    override fun stop(entity: NpcEntity) {
        entity.vehicleTransport = false
        entity.navigation.stop()
    }

    override fun tick(entity: NpcEntity) {
        // VehicleTransportBehaviour may stop when Fight becomes active; the permanent crew contract
        // still applies while the assigned vehicle is operational.
        entity.vehicleTransport = true
        val vehicle = assignedVehicle(entity) ?: return
        if (entity.vehicle === vehicle) {
            entity.navigation.stop()
            return
        }
        if (entity.vehicle != null) entity.stopRiding()

        if (vehicle.boundingBox.distanceToSqr(entity.position()) > BOARD_DISTANCE_SQR) {
            val hull = vehicle.boundingBox
            entity.navigation.moveTo(
                entity.x.coerceIn(hull.minX, hull.maxX),
                entity.y.coerceIn(hull.minY, hull.maxY),
                entity.z.coerceIn(hull.minZ, hull.maxZ),
                BOARD_SPEED
            )
            return
        }
        entity.navigation.stop()
        entity.startRiding(vehicle, false)
    }

    private companion object {
        const val BOARD_DISTANCE_SQR = 2.5 * 2.5
        const val BOARD_SPEED = 1.0
        const val RECOVERY_RANGE = 32.0
    }
}

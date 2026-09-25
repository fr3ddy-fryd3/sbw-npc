package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/** Keeps a spawned vehicle crewman with its assigned vehicle until that vehicle is wrecked. */
class VehicleCrewBehaviour : ExtendedBehaviour<NpcEntity>() {
    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private var nextRecoverySearchTick = 0

    private fun assignedVehicle(entity: NpcEntity): Entity? {
        val level = entity.level() as? ServerLevel ?: return null
        if (entity.assignedVehicleId == null && entity.npcClass == NpcClass.TANK_CREW &&
            entity.tickCount >= nextRecoverySearchTick
        ) {
            // A TANK_CREW whose tank is gone would otherwise run this box query every tick forever.
            nextRecoverySearchTick = entity.tickCount + RECOVERY_SEARCH_INTERVAL_TICKS
            // Repairs crews spawned by the earlier T-90 preset, which seated them but failed to
            // persist the assignment. Only a same-faction T-90 can become their vehicle.
            val vehicle = entity.vehicle?.takeIf(::isT90)
                ?: Ports.vehicles.within(
                    level,
                    AABB.ofSize(entity.position(), RECOVERY_RANGE * 2, RECOVERY_RANGE * 2, RECOVERY_RANGE * 2)
                ).filter(::isT90)
                    .filter { SquadTeams.factionOf(it) == SquadTeams.factionOf(entity) }
                    .minByOrNull { entity.distanceToSqr(it) }
            entity.assignedVehicleId = vehicle?.uuid
        }
        val id = entity.assignedVehicleId ?: return null
        val vehicle = level.getEntity(id)
        if (!Ports.vehicles.isOperational(vehicle)) {
            entity.assignedVehicleId = null
            return null
        }
        // A pilot who bailed out of a helicopter that can't fly stays out while there's a fight —
        // climbing back into a grounded aircraft under fire is the one thing that won't help.
        // Still assigned: once it's quiet the crew goes back to it.
        if (vehicle != null && entity.vehicle !== vehicle && entity.target != null &&
            Helicopters.isHelicopter(vehicle) && !Helicopters.canFly(vehicle)
        ) return null
        return vehicle
    }

    private fun isT90(vehicle: Entity): Boolean = Ports.vehicles.modelOf(vehicle) == TankModel.T_90A

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { assignedVehicle(entity) != null }
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = assignedVehicle(entity) != null

    override fun start(entity: NpcEntity) {
        entity.vehicleTransport = true
    }

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
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
            entity.navigateTo(
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
        const val RECOVERY_SEARCH_INTERVAL_TICKS = 40
        const val START_CHECK_INTERVAL_TICKS = 5
    }
}

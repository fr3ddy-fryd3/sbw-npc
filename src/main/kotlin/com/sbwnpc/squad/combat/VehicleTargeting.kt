package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

/** A vehicle is targeted through a hostile occupant because SBW's gunner API accepts LivingEntity targets. */
object VehicleTargeting {
    fun closestVisibleHostileVehicleOccupant(
        entity: NpcEntity,
        level: ServerLevel,
        range: Double
    ): LivingEntity? {
        val box = AABB.ofSize(entity.position(), range * 2, range * 2, range * 2)
        val candidates = level.getEntitiesOfClass(LivingEntity::class.java, box) {
            it.isAlive && SquadTeams.isHostile(entity, it) &&
                it.vehicle is VehicleEntity && it.vehicle !== entity.vehicle
        }
        return TargetSelection.nearestVisible(candidates, { entity.distanceToSqr(it) }) {
            entity.sensing.hasLineOfSight(it.vehicle!!)
        }
    }
}

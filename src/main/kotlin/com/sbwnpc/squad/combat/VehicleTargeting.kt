package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

/** A vehicle is targeted through a hostile occupant because SBW's gunner API accepts LivingEntity targets. */
object VehicleTargeting {
    // Vehicles drive on the ground like everyone else — a box `range` tall in every direction
    // (144 blocks at detection range) walked ~10x the chunk sections it needed to.
    private const val SEARCH_HEIGHT = 24.0

    fun closestVisibleHostileVehicleOccupant(
        entity: NpcEntity,
        level: ServerLevel,
        range: Double
    ): LivingEntity? {
        val box = AABB.ofSize(entity.position(), range * 2, SEARCH_HEIGHT * 2, range * 2)
        // Vehicle-borne hostiles only, cheapest filters first; the raycast is done last and only
        // until the first (nearest) visible one — not for every candidate.
        val occupants = level.getEntitiesOfClass(LivingEntity::class.java, box) {
            it.isAlive && it.vehicle is VehicleEntity && it.vehicle !== entity.vehicle && SquadTeams.isHostile(entity, it)
        }
        if (occupants.isEmpty()) return null
        val rangeSqr = range * range
        occupants.sortBy { entity.distanceToSqr(it) }
        for (occupant in occupants) {
            if (entity.distanceToSqr(occupant) > rangeSqr) break
            if (entity.sensing.hasLineOfSight(occupant.vehicle!!)) return occupant
        }
        return null
    }
}

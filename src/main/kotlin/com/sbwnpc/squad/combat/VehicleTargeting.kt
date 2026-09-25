package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
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
            it.isAlive && Ports.vehicles.isVehicle(it.vehicle) && it.vehicle !== entity.vehicle && SquadTeams.isHostile(entity, it)
        }
        if (occupants.isEmpty()) return null
        val rangeSqr = range * range
        occupants.sortBy { entity.distanceToSqr(it) }
        for (occupant in occupants) {
            if (entity.distanceToSqr(occupant) > rangeSqr) break
            // Same arc the naked eye gets in SquadTargetSensor — a tank behind you is no more
            // visible than a rifleman behind you.
            if (!Vision.inCone(entity.position(), entity.yHeadRot, occupant.position())) continue
            if (entity.sensing.hasLineOfSight(occupant.vehicle!!)) return occupant
        }
        return null
    }

    /**
     * Helicopters are spotted from twice as far as anything on the ground and up to
     * [AIR_SEARCH_HEIGHT] above or below: they are loud, they fly in the open, and a ground-sized
     * search box left NPCs blind to an aircraft hovering twenty blocks over their heads.
     *
     * A box that size would walk a lot of chunk sections for every NPC's scan, so the level's
     * helicopters are listed once every [AIRCRAFT_REFRESH_TICKS] and each scan just looks
     * through that short list.
     */
    fun closestVisibleHostileAircrew(entity: NpcEntity, level: ServerLevel, groundRange: Double): LivingEntity? {
        val range = groundRange * AIR_RANGE_FACTOR
        val rangeSqr = range * range
        var best: LivingEntity? = null
        var bestSqr = Double.MAX_VALUE
        for (heli in aircraft(level)) {
            if (!heli.isAlive || heli === entity.vehicle) continue
            if (Math.abs(heli.y - entity.y) > AIR_SEARCH_HEIGHT) continue
            val distSqr = entity.distanceToSqr(heli)
            if (distSqr > rangeSqr || distSqr >= bestSqr) continue
            val occupant = heli.passengers.firstOrNull {
                it is LivingEntity && it.isAlive && SquadTeams.isHostile(entity, it)
            } as? LivingEntity ?: continue
            if (!Vision.inCone(entity.position(), entity.yHeadRot, heli.position())) continue
            // Not hasLineOfSight: vanilla gives up past 128 blocks, well inside this range.
            if (Sightline.blocked(level, entity.eyePosition, heli.boundingBox.center, entity)) continue
            best = occupant
            bestSqr = distSqr
        }
        return best
    }

    /** Whether [target] is flying one, for callers that range-check a target they were handed. */
    fun isAircrew(target: Entity): Boolean = target.vehicle?.let { Helicopters.isHelicopter(it) } == true

    private const val AIR_RANGE_FACTOR = 2.0
    private const val AIR_SEARCH_HEIGHT = 50.0
    private const val AIRCRAFT_REFRESH_TICKS = 10L

    private class AircraftList(val at: Long, val list: List<Entity>)
    private val aircraftByLevel = HashMap<ResourceKey<Level>, AircraftList>()

    private fun aircraft(level: ServerLevel): List<Entity> {
        val cached = aircraftByLevel[level.dimension()]
        if (cached != null && level.gameTime - cached.at < AIRCRAFT_REFRESH_TICKS) return cached.list
        val list = level.allEntities.filter { Helicopters.isHelicopter(it) }
        aircraftByLevel[level.dimension()] = AircraftList(level.gameTime, list)
        return list
    }

    fun rangeFor(target: Entity, groundRange: Double): Double =
        if (isAircrew(target)) groundRange * AIR_RANGE_FACTOR else groundRange
}

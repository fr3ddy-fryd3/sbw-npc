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
    /**
     * Mounted gunners select reachable body shots; other observers keep their normal target choices.
     *
     * A target the gunner cannot see is judged by the gun's limits only: an enemy heard, or passed on
     * by the squad, has no muzzle sightline by definition, and requiring one left the turret blind to
     * everything but what it saw itself (in-game 24.10). The fire gate still needs a clear muzzle.
     */
    fun weaponPermits(entity: NpcEntity, target: LivingEntity): Boolean {
        val vehicle = entity.vehicle?.takeIf { Ports.vehicles.hasWeaponAt(it, entity) } ?: return true
        return if (DetectionSightline.canSee(entity, target)) Ports.vehicles.canAimAt(vehicle, entity, target) else Ports.vehicles.canReach(vehicle, entity, target)
    }

    /** Whether a mounted gunner's gun can be fired at [target] right now; true for everyone else. */
    fun shootableNow(entity: NpcEntity, target: LivingEntity): Boolean {
        val vehicle = entity.vehicle?.takeIf { Ports.vehicles.hasWeaponAt(it, entity) } ?: return true
        return Ports.vehicles.canAimAt(vehicle, entity, target)
    }

    // Vehicles drive on the ground like everyone else — a box `range` tall in every direction
    // (144 blocks at detection range) walked ~10x the chunk sections it needed to.
    private const val SEARCH_HEIGHT = 24.0

    fun closestVisibleHostileVehicleOccupant(
        entity: NpcEntity,
        level: ServerLevel,
        range: Double,
        accept: (LivingEntity) -> Boolean = { true },
    ): LivingEntity? {
        // From a helicopter the ground is a long way down: search as far as the ground can see it.
        val airborne = isAircrew(entity)
        val height = if (airborne) AIR_SEARCH_HEIGHT else SEARCH_HEIGHT

        @Suppress("NAME_SHADOWING")
        val range = if (airborne) range * AIR_RANGE_FACTOR else range
        val rangeSqr = range * range
        // Walked from the level's short list of vehicles rather than a box query for every living
        // thing within 144 blocks: every NPC ran that one every half second, with a handful of
        // vehicles on the map at most. Cheapest filters first; the raycast is done last and only
        // until the first (nearest) visible one — not for every candidate.
        val occupants = hostileOccupants(entity, level, height, rangeSqr)
        if (occupants.isEmpty()) return null
        occupants.sortBy { entity.distanceToSqr(it) }
        for (occupant in occupants) {
            if (entity.distanceToSqr(occupant) > rangeSqr) break
            // Same arc the naked eye gets in SquadTargetSensor — a tank behind you is no more
            // visible than a rifleman behind you.
            if (!Vision.inCone(entity.position(), entity.yHeadRot, occupant.position())) continue
            if (DetectionSightline.canSee(entity, occupant.vehicle!!) && accept(occupant)) return occupant
        }
        return null
    }

    private fun hostileOccupants(entity: NpcEntity, level: ServerLevel, height: Double, rangeSqr: Double): ArrayList<LivingEntity> {
        val occupants = ArrayList<LivingEntity>()
        for (vehicle in vehicles(level)) {
            if (!vehicle.isAlive || vehicle === entity.vehicle) continue
            if (Math.abs(vehicle.y - entity.y) > height || entity.distanceToSqr(vehicle) > rangeSqr) continue
            for (rider in vehicle.passengers) {
                if (rider is LivingEntity && rider.isAlive && SquadTeams.isHostile(entity, rider)) occupants += rider
            }
        }
        return occupants
    }

    /**
     * Helicopters are spotted from twice as far as anything on the ground and up to
     * [AIR_SEARCH_HEIGHT] above or below: they are loud, they fly in the open, and a ground-sized
     * search box left NPCs blind to an aircraft hovering twenty blocks over their heads. 100, not
     * the 50 it started at: a transport cruising over hills holds 70+ above the ground under it.
     *
     * A box that size would walk a lot of chunk sections for every NPC's scan, so each scan just
     * looks through the level's short list of vehicles ([vehicles]).
     */
    fun closestVisibleHostileAircrew(entity: NpcEntity, level: ServerLevel, groundRange: Double, accept: (LivingEntity) -> Boolean = { true }): LivingEntity? {
        val range = groundRange * AIR_RANGE_FACTOR
        val rangeSqr = range * range
        var best: LivingEntity? = null
        var bestSqr = Double.MAX_VALUE
        for (heli in vehicles(level)) {
            val distSqr = entity.distanceToSqr(heli)
            if (distSqr >= bestSqr) continue
            val occupant = visibleAircrew(entity, heli, level, rangeSqr) ?: continue
            if (!accept(occupant)) continue
            best = occupant
            bestSqr = distSqr
        }
        return best
    }

    private fun visibleAircrew(entity: NpcEntity, heli: Entity, level: ServerLevel, rangeSqr: Double): LivingEntity? {
        if (!Helicopters.isHelicopter(heli)) return null
        if (!heli.isAlive || heli === entity.vehicle) return null
        if (Math.abs(heli.y - entity.y) > AIR_SEARCH_HEIGHT || entity.distanceToSqr(heli) > rangeSqr) return null
        val occupant = heli.passengers.firstOrNull { it is LivingEntity && it.isAlive && SquadTeams.isHostile(entity, it) } as? LivingEntity ?: return null
        if (!Vision.inCone(entity.position(), entity.yHeadRot, heli.position())) return null
        // Not hasLineOfSight: vanilla gives up past 128 blocks, well inside this range.
        return occupant.takeIf { DetectionSightline.visible(level, entity.eyePosition, heli.boundingBox.center, entity) }
    }

    /** Whether [target] is flying one, for callers that range-check a target they were handed. */
    fun isAircrew(target: Entity): Boolean = target.vehicle?.let { Helicopters.isHelicopter(it) } == true

    const val AIR_RANGE_FACTOR = 2.0
    const val AIR_SEARCH_HEIGHT = 100.0
    private const val VEHICLE_REFRESH_TICKS = 10L

    private class VehicleList(val at: Long, val list: List<Entity>)
    private val vehiclesByLevel = HashMap<ResourceKey<Level>, VehicleList>()

    /** Every vehicle in [level], listed afresh at most every [VEHICLE_REFRESH_TICKS] — one walk of
     *  the level's entities shared by every scan in between. May hold one destroyed since. */
    fun vehicles(level: ServerLevel): List<Entity> {
        val cached = vehiclesByLevel[level.dimension()]
        // In range only: a world loaded afresh starts its clock over, and a list from before must not pass.
        if (cached != null && level.gameTime - cached.at in 0 until VEHICLE_REFRESH_TICKS) return cached.list
        val list = level.allEntities.filter(Ports.vehicles::isVehicle)
        vehiclesByLevel[level.dimension()] = VehicleList(level.gameTime, list)
        return list
    }

    fun rangeFor(target: Entity, groundRange: Double): Double = if (isAircrew(target)) groundRange * AIR_RANGE_FACTOR else groundRange
}

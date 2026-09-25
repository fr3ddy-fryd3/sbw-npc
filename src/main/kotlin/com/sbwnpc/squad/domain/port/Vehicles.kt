package com.sbwnpc.squad.domain.port

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB

/** A vehicle the mod knows by name — see the enums under `npc/`. */
interface VehicleModel

/**
 * Vehicles, held as plain entities. Every question takes the entity and answers for anything: a
 * non-vehicle is never operational, never a wreck, has no seats and no model.
 */
interface Vehicles {
    fun isVehicle(entity: Entity?): Boolean

    /** A vehicle that is alive and not a wreck. */
    fun isOperational(entity: Entity?): Boolean

    fun isWreck(vehicle: Entity): Boolean

    fun isLocked(vehicle: Entity): Boolean

    fun seatCount(vehicle: Entity): Int

    /** -1 when [passenger] isn't aboard. */
    fun seatOf(vehicle: Entity, passenger: Entity): Int

    /** Seat by seat, null where a seat is empty. */
    fun seating(vehicle: Entity): List<Entity?>

    /** Whether [passenger]'s seat has a weapon of its own. */
    fun hasWeaponAt(vehicle: Entity, passenger: Entity): Boolean

    /** Enough stored power for roughly [ticks] of driving. Always true for anything that doesn't
     *  run on power. */
    fun hasPowerFor(vehicle: Entity, ticks: Double): Boolean

    fun modelOf(vehicle: Entity): VehicleModel?

    /** A new, unplaced vehicle of [model], or null if it can't be made. */
    fun create(level: ServerLevel, model: VehicleModel): Entity?

    /** Full power and the ammunition a deployed crew starts with. */
    fun fuelAndArm(vehicle: Entity, model: VehicleModel)

    /** Destroys it the way damage would: it becomes a wreck and blows up where it comes down. */
    fun writeOff(vehicle: Entity)

    fun within(level: Level, area: AABB, filter: (Entity) -> Boolean = { true }): List<Entity>
}

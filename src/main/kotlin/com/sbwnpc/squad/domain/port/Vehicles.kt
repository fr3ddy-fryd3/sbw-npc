package com.sbwnpc.squad.domain.port

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** A vehicle the mod knows by name — see the enums under `npc/`. */
interface VehicleModel

/** What a vehicle moves through. */
enum class Mobility { GROUND, AIR, WATER, FIXED }

/** A cannon round: armour-piercing for vehicles, high-explosive for everything else. */
enum class CannonRound { ARMOUR_PIERCING, HIGH_EXPLOSIVE }

/** How fast a rotorcraft's airframe answers the cyclic, per axis; 1.0 is SBW's baseline. */
data class TurnRates(val yaw: Float, val pitch: Float)

/** The steering input chosen on one tick of [Vehicles.driveToward]; [detail] is for the trace log. */
data class Steering(val right: Boolean, val left: Boolean, val detail: String)

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

    // --- Driving (ground vehicles; the driver is whoever holds seat 0) ---

    /** Null for anything that isn't a vehicle. */
    fun mobility(vehicle: Entity): Mobility?

    /** Throttle forward and steer toward [point] for one tick. */
    fun driveToward(vehicle: Entity, point: Vec3): Steering

    /** Back up for one tick, turning to one side. */
    fun reverse(vehicle: Entity, turnLeft: Boolean)

    /** Lets go of every pedal. The vehicle coasts. */
    fun release(vehicle: Entity)

    /** Kills the throttle outright, so it stops within a few ticks instead of coasting. */
    fun cutPower(vehicle: Entity)

    /** The whole hull, including parts that stick out of the entity's own box. */
    fun hull(vehicle: Entity): AABB

    /** Whether the hull, moved by [offset], would run into [other]. */
    fun wouldHit(vehicle: Entity, other: Entity, offset: Vec3): Boolean

    // --- Damage ---

    /** Game time of the last hit taken; grows with every hit. */
    fun lastHitTime(vehicle: Entity): Long

    fun lastAttacker(vehicle: Entity): Entity?

    // --- Weapons ---

    /** Whether [passenger] can fire the weapon at its seat right now. */
    fun canFire(vehicle: Entity, passenger: LivingEntity): Boolean

    /** Whether the weapon at [seat] can fire right now, whoever takes the seat. */
    fun seatReady(vehicle: Entity, seat: Int): Boolean

    /** Whether [passenger]'s weapon has anything left to fire. A reload or a cooling barrel still
     *  counts as having ammo. */
    fun seatHasAmmo(vehicle: Entity, passenger: Entity): Boolean

    /** Points the turret or weapon station [gunner] controls at [target]; null stands it down. */
    fun aimAt(vehicle: Entity, gunner: Entity, target: LivingEntity?)

    /** Selects [weapon] at [seat] and loads it with [round]. */
    fun loadRound(vehicle: Entity, seat: Int, weapon: Int, round: CannonRound)

    // --- Condition ---

    /** Audible from a distance. */
    fun engineRunning(vehicle: Entity): Boolean

    /** Stored power in the vehicle's own units; zero for anything with no battery. */
    fun storedPower(vehicle: Entity): Int

    /** Health left, 0..1. */
    fun healthFraction(vehicle: Entity): Float

    // --- Rotorcraft (the pilot is whoever holds seat 0) ---

    fun roll(heli: Entity): Float

    /** Rotor speed: how much authority the controls have right now, 0 on the ground. */
    fun rotorSpeed(heli: Entity): Float

    fun throttle(heli: Entity): Float

    /** Null until the engine has run at least once. */
    fun turnRates(heli: Entity): TurnRates?

    /** Collective: [climb], [sinkSlow] and [sinkFast] are separate inputs; all false holds. */
    fun setCollective(heli: Entity, climb: Boolean, sinkSlow: Boolean, sinkFast: Boolean)

    /** Cyclic stick deflection, the same units a player's mouse gives it. */
    fun setCyclic(heli: Entity, x: Float, y: Float)

    /** Hover mode keeps the airframe level and kills drift. */
    fun setHover(heli: Entity, on: Boolean)

    /** The pedals, which roll the airframe. */
    fun setRollInputs(heli: Entity, left: Boolean, right: Boolean)

    /** Every rotorcraft control to neutral. */
    fun neutralControls(heli: Entity)

    /** Stops the engine rather than leaving it idling. */
    fun shutDownEngine(heli: Entity)
}

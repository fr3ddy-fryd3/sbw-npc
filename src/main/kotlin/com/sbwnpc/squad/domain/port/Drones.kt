package com.sbwnpc.squad.domain.port

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/** Drones, held as plain entities. Alive/wreck/health checks go through [Vehicles]. */
interface Drones {
    /** A remote-piloted drone — what an operator flies and what infantry shoots down. */
    fun isPiloted(entity: Entity?): Boolean

    /** Any drone, including swarm munitions that fly themselves. */
    fun isDrone(entity: Entity?): Boolean

    /** The player at the controls, if any. */
    fun controllerOf(drone: Entity): Entity?

    /** A new, unplaced kamikaze drone. */
    fun createKamikaze(level: ServerLevel): Entity

    /** Whether this drone's own destruction sets its warhead off (see [crash]); otherwise the
     *  operator has to call [explodeWarhead]. */
    fun blowsUpOnCrash(drone: Entity): Boolean

    /** Blast radius of a kamikaze drone's warhead, for the "allies in the blast" check. */
    fun warheadRadius(blowsUpOnCrash: Boolean): Double

    /** Destroys a drone that [blowsUpOnCrash]; its own explosion follows. */
    fun crash(drone: Entity)

    /** Sets off a kamikaze warhead at [at], credited to [operator]. [drone] may already be gone. */
    fun explodeWarhead(level: ServerLevel, operator: Entity, drone: Entity?, at: Vec3)

    /** Flight inputs for one tick. Heading is the drone's own yRot. */
    fun setInputs(drone: Entity, forward: Boolean, back: Boolean, up: Boolean, down: Boolean)

    /** What an operator holds while flying. */
    fun monitor(): ItemStack

    fun isMonitor(stack: ItemStack): Boolean
}

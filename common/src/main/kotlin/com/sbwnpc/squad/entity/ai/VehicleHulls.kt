package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.AABB

/** Numbers the node evaluators of every Minecraft version share. */
internal object NodeRules {
    const val SEARCH_RADIUS = 24.0

    /** Taller than this (a carpet, a snow layer, a bottom slab are lower) can't be stepped over. */
    const val STEP_OVER = 0.5

    /** Keeps routes from hugging the hull close enough to catch on it. */
    const val CLEARANCE = 0.3
}

/**
 * The vehicles near a mob while a path is planned, as terrain for [VehicleAwareNodeEvaluator].
 * Gathered once per search by [refresh] and dropped by [clear], so a vehicle that drives off
 * leaves no phantom walls behind.
 */
internal class VehicleHulls {
    /** Vehicles near the mob, each with the box round its hull for a quick first test. */
    private var hulls: List<Pair<Entity, AABB>> = emptyList()
    private var standingOn: BlockPos? = null

    fun refresh(mob: Mob) {
        val ridden = mob.vehicle
        hulls = Ports.vehicles
            .within(mob.level(), mob.boundingBox.inflate(NodeRules.SEARCH_RADIUS)) { vehicle ->
                Ports.vehicles.isOperational(vehicle) && vehicle !== ridden
            }
            // The whole hull: a BMP's box is a 3.6-block square round its middle, its hull twice as
            // long, and paths planned through the nose left men pressed against it.
            .map { it to Ports.vehicles.hull(it).inflate(NodeRules.CLEARANCE) }
            // One he's already up against or inside of doesn't count: every way out of it would
            // be a wall, and he'd stand there with a one-node path while it waits for him.
            .filterNot { (vehicle, _) -> Ports.vehicles.occupies(vehicle, mob.boundingBox, NodeRules.CLEARANCE) }
        // Whatever the mob is standing in stays passable. Blocking it would leave the path with no
        // valid start at all, which is precisely the situation of a mob that has already been
        // pushed up onto a hull and now needs a route off it.
        standingOn = if (hulls.isEmpty()) null else mob.blockPosition()
    }

    fun clear() {
        hulls = emptyList()
        standingOn = null
    }

    /** Whether the node cell at [x], [y], [z] is inside a vehicle's hull (and not the one the mob stands in). */
    fun blocks(x: Int, y: Int, z: Int): Boolean = hulls.isNotEmpty() && !isStandingOn(x, y, z) && occupied(x, y, z)

    private fun isStandingOn(x: Int, y: Int, z: Int): Boolean {
        val pos = standingOn ?: return false
        return pos.x == x && pos.z == z && Math.abs(pos.y - y) <= 1
    }

    private fun occupied(x: Int, y: Int, z: Int): Boolean {
        val node = AABB(
            x.toDouble(),
            y.toDouble(),
            z.toDouble(),
            x + 1.0,
            y + 1.0,
            z + 1.0,
        )
        // The box round a hull turned across the axes is far bigger than the hull — a BMP at an
        // angle boxed in men standing well clear of it — so only the hull itself blocks.
        return hulls.any { (vehicle, box) -> box.intersects(node) && Ports.vehicles.occupies(vehicle, node, NodeRules.CLEARANCE) }
    }
}

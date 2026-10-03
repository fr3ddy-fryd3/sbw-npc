package com.sbwnpc.squad.route

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * A vehicle on the ground, for [CellPlanner]: its hull [halfWidth] blocks either side of its middle
 * over ground ([Ground]) no rougher than it can climb ([climb], its own step height), with
 * [headroom] blocks free over it — no trunks, nothing that burns. Amphibious engines can also
 * cross water at its surface, while the crew only gets off onto land.
 *
 * It drives to the goal or the next stretch of known ground whenever it can. A stop far short of
 * the goal is a fallback only when no continuation can be found, rather than an alternative that
 * wins merely because a straight-line estimate of the crew's walk is cheaper than a detour.
 */
class Driving(
    private val ground: Ground,
    halfWidth: Double,
    climb: Double,
    height: Double,
    private val canCrossWater: Boolean = false,
) : CellPlanner.Medium {
    constructor(level: ServerLevel, halfWidth: Double, climb: Double, height: Double, canCrossWater: Boolean = false) :
        this(Ground.of(level), halfWidth, climb, height, canCrossWater)

    private val clearance = Math.max(0, Math.ceil(halfWidth - 0.5).toInt())
    private val climb = Math.max(1, Math.floor(climb).toInt())
    private val headroom = Math.min(GroundMap.MAX_ROOM, Math.max(2, Math.ceil(height).toInt()))
    private val fits = HashMap<Long, Boolean>()

    override val pace = 1.0 / DRIVE_PACE

    override fun known(x: Int, z: Int) = ground.known(x, z)

    override fun open(x: Int, z: Int): Boolean = fits.getOrPut(BlockPos.asLong(x, 0, z)) {
        if (!ground(x, z)) return@getOrPut false
        val h = ground.height(x, z)
        for (dx in -clearance..clearance) for (dz in -clearance..clearance) {
            if (dx == 0 && dz == 0) continue
            if (!ground(x + dx, z + dz) || Math.abs(ground.height(x + dx, z + dz) - h) > climb) return@getOrPut false
        }
        true
    }

    private fun ground(x: Int, z: Int): Boolean {
        val kind = ground.kind(x, z)
        return (kind == GroundMap.Kind.GROUND || kind == GroundMap.Kind.NO_ROOM ||
            (canCrossWater && kind == GroundMap.Kind.WATER)) && ground.room(x, z) >= headroom
    }

    override fun step(ax: Int, az: Int, bx: Int, bz: Int) =
        Math.abs(ground.height(bx, bz) - ground.height(ax, az)) <= climb

    override fun extraCost(x: Int, z: Int) = 0.0

    override fun exitAt(x: Int, z: Int, goal: Vec3): Vec3? =
        if (ground.kind(x, z) == GroundMap.Kind.WATER) null else Vec3(x + 0.5, pointY(x, z), z + 0.5)

    override fun preferredExit(exit: Vec3, goal: Vec3): Boolean =
        Math.hypot(exit.x - goal.x, exit.z - goal.z) <= GOAL_RADIUS

    // A known detour is fine. Driving back to an unknown frontier farther from the objective
    // just guesses that the water or cliff ahead will vanish on the other side of it; walk on
    // from the best reachable bank instead. Walking and boat exploration keep their own rules.
    override fun canExplore(from: Vec3, frontier: Vec3, goal: Vec3): Boolean =
        Math.hypot(frontier.x - goal.x, frontier.z - goal.z) < Math.hypot(from.x - goal.x, from.z - goal.z)

    override fun pointY(x: Int, z: Int) = ground.height(x, z).toDouble()

    companion object {
        /** A vehicle covers ground this many times faster than a man walking. */
        const val DRIVE_PACE = 2.0
        private const val GOAL_RADIUS = 20.0
    }
}

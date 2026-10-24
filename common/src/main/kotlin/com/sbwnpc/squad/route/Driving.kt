package com.sbwnpc.squad.route

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
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
    private val costs = Long2DoubleOpenHashMap()
    private var avoided: Set<Long> = emptySet()

    /** Excludes columns learned from collisions during this trip, without changing the world map. */
    fun avoiding(columns: Set<Long>): Driving = apply {
        avoided = columns.toSet()
        fits.clear()
        costs.clear()
    }

    override val pace = 1.0 / DRIVE_PACE

    override fun known(x: Int, z: Int) = ground.known(x, z)

    override fun open(x: Int, z: Int): Boolean =
        fits.getOrPut(BlockPos.asLong(x, 0, z)) {
            if (!ground(x, z)) return@getOrPut false
            for (dx in -clearance..clearance) {
                for (dz in -clearance..clearance) {
                    if (dx == 0 && dz == 0) continue
                    if (!ground(x + dx, z + dz)) return@getOrPut false
                }
            }
            true
        }

    private fun ground(x: Int, z: Int): Boolean {
        if (BlockPos.asLong(x, 0, z) in avoided) return false
        val kind = ground.kind(x, z)
        return (
            kind == GroundMap.Kind.GROUND || kind == GroundMap.Kind.NO_ROOM ||
                (canCrossWater && kind == GroundMap.Kind.WATER)
            ) && ground.room(x, z) >= headroom
    }

    override fun step(ax: Int, az: Int, bx: Int, bz: Int): Boolean {
        // Step height limits a change while the hull moves, not the total rise across its width.
        // A five-block footprint on a climbable slope can span four blocks of height. Comparing
        // every corner with its middle incorrectly cut that slope into isolated flat patches.
        for (dx in -clearance..clearance) {
            for (dz in -clearance..clearance) {
                if (Math.abs(ground.height(bx + dx, bz + dz) - ground.height(ax + dx, az + dz)) > climb) return false
            }
        }
        return true
    }

    override fun extraCost(x: Int, z: Int): Double {
        val key = BlockPos.asLong(x, 0, z)
        if (costs.containsKey(key)) return costs.get(key)
        return clearanceCost(x, z).also { costs.put(key, it) }
    }

    private fun clearanceCost(x: Int, z: Int): Double {
        for (margin in 1..CLEARANCE_MARGIN) {
            val radius = clearance + margin
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue
                    val px = x + dx
                    val pz = z + dz
                    if (!ground.known(px, pz)) continue
                    val step = Math.abs(ground.height(px, pz) - ground.height(px - dx.compareTo(0), pz - dz.compareTo(0)))
                    if (!ground(px, pz) || step > climb) return CLEARANCE_COST / (margin * margin)
                }
            }
        }
        return 0.0
    }

    override fun exitAt(x: Int, z: Int, goal: Vec3): Vec3? = if (ground.kind(x, z) == GroundMap.Kind.WATER) null else Vec3(x + 0.5, pointY(x, z), z + 0.5)

    override fun preferredExit(exit: Vec3, goal: Vec3): Boolean = Math.hypot(exit.x - goal.x, exit.z - goal.z) <= GOAL_RADIUS

    // A known detour is fine. Driving back to an unknown frontier farther from the objective
    // just guesses that the water or cliff ahead will vanish on the other side of it; walk on
    // from the best reachable bank instead. Walking and boat exploration keep their own rules.
    override fun canExplore(from: Vec3, frontier: Vec3, goal: Vec3): Boolean = Math.hypot(frontier.x - goal.x, frontier.z - goal.z) < Math.hypot(from.x - goal.x, from.z - goal.z)

    override fun pointY(x: Int, z: Int) = ground.height(x, z).toDouble()

    companion object {
        /** A vehicle covers ground this many times faster than a man walking. */
        const val DRIVE_PACE = 2.0
        private const val GOAL_RADIUS = 20.0
        private const val CLEARANCE_MARGIN = 2
        private const val CLEARANCE_COST = 4.0
    }
}

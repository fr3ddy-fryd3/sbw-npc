package com.sbwnpc.squad.route

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * A man on foot, for [CellPlanner]: over ground and through water ([Ground]), up a block at a step
 * and down three at most, never through a trunk, a low canopy or anything that burns.
 *
 * The walk ends at the goal. Where it can't be got to — up a cliff, inside walls — it ends as near
 * as the ground allows, the rest of the way counted at [SHORT_OF_GOAL] times its length so that
 * getting there is always worth a detour first; the ordinary short search takes it from there.
 */
class Walking(private val ground: Ground) : CellPlanner.Medium {
    constructor(level: ServerLevel) : this(Ground.of(level))

    override val pace = 1.0

    override fun known(x: Int, z: Int) = ground.known(x, z)

    override fun open(x: Int, z: Int): Boolean {
        val kind = ground.kind(x, z)
        return kind == GroundMap.Kind.GROUND || kind == GroundMap.Kind.WATER
    }

    override fun step(ax: Int, az: Int, bx: Int, bz: Int): Boolean {
        val rise = ground.height(bx, bz) - ground.height(ax, az)
        return rise <= MAX_CLIMB && rise >= -MAX_DROP
    }

    override fun extraCost(x: Int, z: Int): Double =
        if (ground.kind(x, z) == GroundMap.Kind.WATER) SWIM_COST else 0.0

    override fun exitAt(x: Int, z: Int, goal: Vec3): Vec3 = Vec3(x + 0.5, pointY(x, z), z + 0.5)

    override fun remaining(exit: Vec3, goal: Vec3): Double {
        val d = Math.hypot(exit.x - goal.x, exit.z - goal.z)
        return if (d <= AT_GOAL) d else d * SHORT_OF_GOAL
    }

    override fun pointY(x: Int, z: Int) = ground.height(x, z).toDouble()

    private companion object {
        const val MAX_CLIMB = 1
        const val MAX_DROP = 3
        /** Extra blocks of walking a block of swimming costs. */
        const val SWIM_COST = 2.0
        /** This near the goal is at it. */
        const val AT_GOAL = 4.0
        const val SHORT_OF_GOAL = 2.0
    }
}

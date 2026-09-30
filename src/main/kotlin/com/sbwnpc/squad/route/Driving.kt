package com.sbwnpc.squad.route

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * A vehicle on the ground, for [CellPlanner]: its hull [halfWidth] blocks either side of its middle
 * over ground ([GroundMap]) no rougher than it can climb ([climb], its own step height), with
 * [headroom] blocks free over it — no water, no trunks, nothing that burns.
 *
 * It can stop anywhere and its crew walk on: the trip ends wherever driving on would cost more than
 * getting out — at the goal where the ground lets it get there, at the foot of the climb where it
 * doesn't.
 */
class Driving(
    private val level: ServerLevel,
    halfWidth: Double,
    climb: Double,
    height: Double,
) : CellPlanner.Medium {
    private val clearance = Math.max(0, Math.ceil(halfWidth - 0.5).toInt())
    private val climb = Math.max(1, Math.floor(climb).toInt())
    private val headroom = Math.min(GroundMap.MAX_ROOM, Math.max(2, Math.ceil(height).toInt()))
    private val fits = HashMap<Long, Boolean>()

    override val pace = 1.0 / DRIVE_PACE

    override fun known(x: Int, z: Int) = GroundMap.known(level, x, z)

    override fun open(x: Int, z: Int): Boolean = fits.getOrPut(BlockPos.asLong(x, 0, z)) {
        if (!ground(x, z)) return@getOrPut false
        val h = GroundMap.height(level, x, z)
        for (dx in -clearance..clearance) for (dz in -clearance..clearance) {
            if (dx == 0 && dz == 0) continue
            if (!ground(x + dx, z + dz) || Math.abs(GroundMap.height(level, x + dx, z + dz) - h) > climb) return@getOrPut false
        }
        true
    }

    private fun ground(x: Int, z: Int): Boolean {
        val kind = GroundMap.kind(level, x, z)
        return (kind == GroundMap.Kind.GROUND || kind == GroundMap.Kind.NO_ROOM) && GroundMap.room(level, x, z) >= headroom
    }

    override fun step(ax: Int, az: Int, bx: Int, bz: Int) =
        Math.abs(GroundMap.height(level, bx, bz) - GroundMap.height(level, ax, az)) <= climb

    override fun extraCost(x: Int, z: Int) = 0.0

    override fun exitAt(x: Int, z: Int, goal: Vec3): Vec3 = Vec3(x + 0.5, pointY(x, z), z + 0.5)

    override fun pointY(x: Int, z: Int) = GroundMap.height(level, x, z).toDouble()

    companion object {
        /** A vehicle covers ground this many times faster than a man walking. */
        const val DRIVE_PACE = 2.0
    }
}

package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.phys.Vec3

/**
 * A driving route sized to the vehicle, not to the man at the wheel.
 *
 * Routes used to come from the driver's own pathfinder, which plans for a body 0.6 wide and 1.8
 * tall: through the one-block gap between two trunks, under a canopy two blocks up. A hull three
 * or four blocks across and nearly as tall drove into both and stuck there. The same search run
 * with the node evaluator told the vehicle's own size checks every block the hull would pass
 * through — leaves at turret height included.
 *
 * Each search aims up to [LEG] blocks toward the destination, over [SEARCH_RANGE] of driving, and
 * only one runs per server tick.
 */
object VehicleRoutes {
    private const val LEG = 96.0
    private const val SEARCH_RANGE = 140f
    private const val NODES = 10_000
    private var lastPlanTick = Long.MIN_VALUE / 2

    /** A route for [vehicle] driven by [driver] toward [home] as vehicle-centre points, or null
     *  when no search could run this tick or none was found. */
    fun plan(driver: NpcEntity, vehicle: Entity, home: Vec3): Path? {
        val level = driver.level() as? ServerLevel ?: return null
        if (lastPlanTick == level.gameTime) return null
        lastPlanTick = level.gameTime
        val target = legToward(level, vehicle.position(), home) ?: return null
        val width = Math.ceil(vehicle.bbWidth.toDouble()).toInt().coerceAtLeast(1)
        val height = Math.ceil(vehicle.bbHeight.toDouble()).toInt().coerceAtLeast(2)
        val from = vehicle.blockPosition()
        val r = SEARCH_RANGE.toInt() + 8
        val region = PathNavigationRegion(level, from.offset(-r, -r, -r), from.offset(r, r, r))
        return PathFinder(HullEvaluator(width, height), NODES).findPath(region, driver, setOf(target), SEARCH_RANGE, 2, 1f)
    }

    /** Nodes are the hull's lowest corner; this is where its middle is. */
    fun centreOf(node: BlockPos, vehicle: Entity): Vec3 {
        val half = Math.ceil(vehicle.bbWidth.toDouble()) / 2.0
        return Vec3(node.x + half, node.y.toDouble(), node.z + half)
    }

    private fun legToward(level: ServerLevel, from: Vec3, home: Vec3): BlockPos? {
        val dx = home.x - from.x
        val dz = home.z - from.z
        val len = Math.sqrt(dx * dx + dz * dz)
        if (len < 1.0) return BlockPos.containing(home)
        var reach = minOf(len, LEG)
        while (reach >= 8.0) {
            val x = Math.floor(from.x + dx / len * reach).toInt()
            val z = Math.floor(from.z + dz / len * reach).toInt()
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4)
            if (chunk != null) {
                return BlockPos(x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1, z)
            }
            reach -= 8.0
        }
        return null
    }

    /** The ordinary evaluator, checking a [width] x [height] x [width] box at every node. */
    private class HullEvaluator(private val width: Int, private val height: Int) : VehicleAwareNodeEvaluator() {
        override fun prepare(level: PathNavigationRegion, mob: Mob) {
            super.prepare(level, mob)
            entityWidth = width
            entityHeight = height
            entityDepth = width
        }
    }
}

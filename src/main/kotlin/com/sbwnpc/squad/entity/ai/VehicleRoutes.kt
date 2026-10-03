package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.route.GroundMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
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
    /** A leg ending this near its end point got there — see SquadMarch's LEG_REACH. */
    private const val LEG_REACH = 8
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
        val stepHeight = driver.getAttribute(Attributes.STEP_HEIGHT)
        val previousStep = stepHeight?.baseValue
        val waterCost = driver.getPathfindingMalus(PathType.WATER)
        val bankCost = driver.getPathfindingMalus(PathType.WATER_BORDER)
        val amphibious = Ports.vehicles.canCrossWater(vehicle)
        try {
            stepHeight?.baseValue = vehicle.maxUpStep().toDouble()
            // The pathfinder uses the hull's capabilities, then restores the infantry's costs
            // even if a search fails. Floating nodes stay at the surface rather than the bottom.
            driver.setPathfindingMalus(PathType.WATER, if (amphibious) 1f else -1f)
            if (amphibious) driver.setPathfindingMalus(PathType.WATER_BORDER, 0f)
            return PathFinder(HullEvaluator(vehicle, width, height, amphibious), NODES)
                .findPath(region, driver, setOf(target), SEARCH_RANGE, LEG_REACH, 1f)
        } finally {
            if (previousStep != null) stepHeight.baseValue = previousStep
            driver.setPathfindingMalus(PathType.WATER, waterCost)
            driver.setPathfindingMalus(PathType.WATER_BORDER, bankCost)
        }
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
    private class HullEvaluator(
        private val vehicle: Entity, private val width: Int, private val height: Int, private val amphibious: Boolean,
    ) : VehicleAwareNodeEvaluator() {

        init {
            setCanFloat(amphibious)
        }

        override fun prepare(level: PathNavigationRegion, mob: Mob) {
            super.prepare(level, mob)
            entityWidth = width
            entityHeight = height
            entityDepth = width
        }

        override fun getStart(): Node {
            val level = vehicle.level() as? ServerLevel
            if (!amphibious || !vehicle.isInWater || level == null) return super.getStart()
            // The driver's seat can be dry above a floating hull: use the hull's waterline.
            return getStartNode(BlockPos(
                kotlin.math.floor(vehicle.x - width / 2.0).toInt(),
                GroundMap.height(level, vehicle.blockX, vehicle.blockZ),
                kotlin.math.floor(vehicle.z - width / 2.0).toInt()
            ))
        }

        override fun getPathTypeOfMob(context: PathfindingContext, x: Int, y: Int, z: Int, mob: Mob): PathType {
            val type = super.getPathTypeOfMob(context, x, y, z, mob)
            return if (!amphibious && type == PathType.WATER) PathType.BLOCKED else type
        }
    }
}

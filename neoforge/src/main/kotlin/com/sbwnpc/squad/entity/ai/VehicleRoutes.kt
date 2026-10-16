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

    /** A completed search, including one that found no path. Null from [tryPlan] means deferred,
     *  which is not evidence of an impassable route. */
    class SearchResult(val path: Path?)

    /** Searches a hull-sized leg from [vehicle] toward [home], deferred if no search can run yet. */
    fun tryPlan(driver: NpcEntity, vehicle: Entity, home: Vec3): SearchResult? {
        val level = driver.level() as? ServerLevel ?: return null
        if (lastPlanTick == level.gameTime) return null
        val target = legToward(level, vehicle.position(), home) ?: return null
        lastPlanTick = level.gameTime
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
            val targetCorner = cornerOf(Vec3(target.x + 0.5, target.y.toDouble(), target.z + 0.5), vehicle.bbWidth)
            return SearchResult(PathFinder(HullEvaluator(vehicle, width, height, amphibious), NODES)
                .findPath(region, driver, setOf(targetCorner), SEARCH_RANGE, LEG_REACH, 1f))
        } finally {
            if (previousStep != null) stepHeight.baseValue = previousStep
            driver.setPathfindingMalus(PathType.WATER, waterCost)
            driver.setPathfindingMalus(PathType.WATER_BORDER, bankCost)
        }
    }

    /** A partial path must take the hull beyond the stop and toward the objective. A complete
     *  path to the next leg may take a detour; a one-node or backwards partial path cannot. */
    internal fun leadsOn(from: Vec3, end: Vec3?, home: Vec3, reachesTarget: Boolean): Boolean {
        if (end == null || Math.hypot(end.x - from.x, end.z - from.z) < LEG_REACH) return false
        return reachesTarget || Math.hypot(end.x - home.x, end.z - home.z) <
            Math.hypot(from.x - home.x, from.z - home.z) - LEG_REACH
    }

    /** Nodes are the hull's lowest corner; this is where its middle is. */
    fun centreOf(node: BlockPos, vehicle: Entity): Vec3 {
        return centreOf(node, vehicle.bbWidth)
    }

    internal fun centreOf(node: BlockPos, width: Float): Vec3 {
        val half = Math.ceil(width.toDouble()) / 2.0
        return Vec3(node.x + half, node.y.toDouble(), node.z + half)
    }

    /** The whole search uses minimum-corner nodes; both the start and goal are hull centres. */
    internal fun cornerOf(position: Vec3, width: Float): BlockPos {
        val half = Math.ceil(width.toDouble()) / 2.0
        return BlockPos(kotlin.math.floor(position.x - half).toInt(),
            kotlin.math.floor(position.y + 0.5).toInt(), kotlin.math.floor(position.z - half).toInt())
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
            var at = cornerOf(vehicle.position(), vehicle.bbWidth)
            if (amphibious && vehicle.isInWater && level != null) {
                at = BlockPos(at.x, GroundMap.height(level, vehicle.blockX, vehicle.blockZ), at.z)
                return getStartNode(at)
            }
            // A seated NPC's coordinates describe the turret, and its tiny box is no basis for
            // the start of a four-block hull. The native evaluator then searched a box shifted
            // two blocks uphill, often buried in the slope, and returned only that blocked node.
            for (dy in 0..Math.ceil(vehicle.maxUpStep().toDouble()).toInt()) {
                val candidate = at.above(dy)
                if (canStartAt(candidate)) return getStartNode(candidate)
            }
            return getStartNode(at)
        }

        override fun getPathTypeOfMob(context: PathfindingContext, x: Int, y: Int, z: Int, mob: Mob): PathType {
            val type = super.getPathTypeOfMob(context, x, y, z, mob)
            return if (!amphibious && type == PathType.WATER) PathType.BLOCKED else type
        }
    }
}

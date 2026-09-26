package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.Level
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB

/**
 * Ground navigation that treats parked vehicles as terrain.
 *
 * Minecraft's pathfinder reads blocks and nothing else — entities are not obstacles to it, which is
 * why third-party navigators list "avoids entities" as a feature of their own. So a mob asked to
 * walk past an APC plots a route straight through the space the APC occupies, and only the
 * vehicle's collision decides what happens next: it gets shoved against the hull, or rides up onto
 * the roof. No amount of care in *choosing* a destination fixes that, because the problem is the
 * walk, not the target.
 *
 * There is no vanilla hook for "this entity is solid to pathfinding", so the node evaluator has to
 * be told directly.
 */
class VehicleAwareNavigation(mob: Mob, level: Level) : GroundPathNavigation(mob, level) {
    override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
        val evaluator = VehicleAwareNodeEvaluator()
        evaluator.setCanPassDoors(true)
        this.nodeEvaluator = evaluator
        return PathFinder(evaluator, maxVisitedNodes)
    }

    /**
     * A destination in a chunk that isn't loaded gets a step toward it instead of no path at all.
     *
     * Vanilla ground navigation gives up outright on such a target (`getChunkNow` is null, the
     * path is null) — so a squad ordered from the map to a point hundreds of blocks off, with the
     * player standing next to it, never took a step. Callers ask again every second or so, and
     * each time the step starts from wherever the mob has got to.
     */
    override fun createPath(pos: BlockPos, accuracy: Int): Path? {
        if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) != null) return super.createPath(pos, accuracy)
        val dx = pos.x + 0.5 - mob.x
        val dz = pos.z + 0.5 - mob.z
        val len = Math.sqrt(dx * dx + dz * dz)
        if (len < 1.0) return null
        var step = FAR_STEP
        while (step >= MIN_FAR_STEP) {
            val x = Math.floor(mob.x + dx / len * step).toInt()
            val z = Math.floor(mob.z + dz / len * step).toInt()
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4)
            if (chunk != null) {
                val y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1
                return super.createPath(BlockPos(x, y, z), accuracy)
            }
            step -= 8.0
        }
        return null
    }

    private companion object {
        /** Inside the NPC's own follow range (48), so the step is one ordinary path. */
        const val FAR_STEP = 40.0
        const val MIN_FAR_STEP = 8.0
    }
}

private class VehicleAwareNodeEvaluator : WalkNodeEvaluator() {
    private var hulls: List<AABB> = emptyList()
    private var standingOn: BlockPos? = null

    /**
     * One entity query for the whole path computation. [done] drops it again, and the evaluator's
     * own per-position cache is cleared there too, so a vehicle that drives off doesn't leave
     * phantom walls behind.
     */
    override fun prepare(level: PathNavigationRegion, mob: Mob) {
        super.prepare(level, mob)
        val ridden = mob.vehicle
        hulls = Ports.vehicles
            .within(mob.level(), mob.boundingBox.inflate(SEARCH_RADIUS)) { vehicle ->
                Ports.vehicles.isOperational(vehicle) && vehicle !== ridden
            }
            .map { it.boundingBox.inflate(CLEARANCE) }
        // Whatever the mob is standing in stays passable. Blocking it would leave the path with no
        // valid start at all, which is precisely the situation of a mob that has already been
        // pushed up onto a hull and now needs a route off it.
        standingOn = if (hulls.isEmpty()) null else mob.blockPosition()
    }

    override fun done() {
        hulls = emptyList()
        standingOn = null
        super.done()
    }

    override fun getPathTypeOfMob(context: PathfindingContext, x: Int, y: Int, z: Int, mob: Mob): PathType {
        if (hulls.isNotEmpty() && !isStandingOn(x, y, z) && occupied(x, y, z)) {
            return PathType.BLOCKED
        }
        return super.getPathTypeOfMob(context, x, y, z, mob)
    }

    private fun isStandingOn(x: Int, y: Int, z: Int): Boolean {
        val pos = standingOn ?: return false
        return pos.x == x && pos.z == z && Math.abs(pos.y - y) <= 1
    }

    private fun occupied(x: Int, y: Int, z: Int): Boolean {
        val node = AABB(
            x.toDouble(), y.toDouble(), z.toDouble(),
            x + 1.0, y + 1.0, z + 1.0
        )
        return hulls.any { it.intersects(node) }
    }

    private companion object {
        const val SEARCH_RADIUS = 24.0
        /** Keeps routes from hugging the hull close enough to catch on it. */
        const val CLEARANCE = 0.3
    }
}

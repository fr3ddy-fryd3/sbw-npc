package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
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
        hulls = mob.level()
            .getEntitiesOfClass(VehicleEntity::class.java, mob.boundingBox.inflate(SEARCH_RADIUS)) { vehicle ->
                vehicle.isAlive && !vehicle.isWreck && vehicle !== ridden
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

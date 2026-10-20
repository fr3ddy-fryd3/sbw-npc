package com.sbwnpc.squad.combat.tactics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/**
 * The route planning a tactical position search needs from an NPC's navigation.
 *
 * Implemented by [com.sbwnpc.squad.entity.ai.VehicleAwareNavigation]; the searches see only this,
 * so `combat` does not depend on the behaviour package.
 */
interface PositionPaths {
    /** Whether a path can be planned now: not mid-jump or falling. */
    val canPlan: Boolean

    /**
     * One search over the terrain graph for any of a small set of equally suitable posts, at their
     * exact height; with [allowDetour], within the same bounded detour a march gets.
     */
    fun createPositionPath(positions: Set<BlockPos>, allowDetour: Boolean = false): Path?

    companion object {
        /** Half a block up from the feet, so a walker on a slab or path block counts as standing on it. */
        private const val FLOOR_PROBE = 0.5

        /** Same grounded-node height as vanilla WalkNodeEvaluator.getStart, including partial floors. */
        fun walkingNode(feet: Vec3): BlockPos = BlockPos.containing(feet.x, feet.y + FLOOR_PROBE, feet.z)
    }
}

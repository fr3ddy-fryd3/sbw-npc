package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.tactics.ReconProgress
import com.sbwnpc.squad.combat.DetectionSightline
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a recon squad may watch its objective from (owner 09.11): only a spot with a clear line of
 * sight to it counts as a post. Over broken ground the ring alone sent the scouts on until they were
 * almost upon the enemy; here they look along an arc facing their approach for a standing spot that
 * sees the objective, higher ground first, and go there.
 */
internal object ReconPostSearch {
    /** Half the arc searched either side of the squad's bearing from the objective. */
    private const val ARC = Math.PI / 3

    private const val ARC_STEPS = 12
    private const val RING_STEP = 4.0
    private const val STEP = 6

    // A stalking scout's eyes, crouched: the spot must show him the objective as he will stand there.
    private const val EYE = 1.27
    private const val ROUTE_CHECKS = 3

    /** The objective is looked at this high above its centre: a man standing there, not its floor. */
    private const val TARGET_HEIGHT = 1.5

    /**
     * The best post between [ReconProgress.MIN_POST_DISTANCE] and [ring] blocks from [objective] on the
     * side of [from]: the highest spot that sees it, the nearer of two at one height. Null when none does.
     */
    fun find(level: Level, objective: Point3, from: Point3, ring: Double, walker: com.sbwnpc.squad.entity.NpcEntity?): Point3? {
        val bearing = atan2(from.z - objective.z, from.x - objective.x)
        val target = Vec3(objective.x, objective.y + TARGET_HEIGHT, objective.z)
        val seeing = ArrayList<BlockPos>()
        var radius = ring
        while (radius >= ReconProgress.MIN_POST_DISTANCE) {
            for (i in -ARC_STEPS..ARC_STEPS) {
                val angle = bearing + ARC * i / ARC_STEPS
                val column = BlockPos.containing(objective.x + cos(angle) * radius, objective.y, objective.z + sin(angle) * radius)
                val feet = standing(level, column) ?: continue
                if (sees(level, Vec3(feet.x + HALF, feet.y + EYE, feet.z + HALF), target)) seeing += feet
            }
            radius -= RING_STEP
        }
        val origin = BlockPos.containing(from.x, from.y, from.z)
        // Higher ground first, the nearer of two at one height; only a spot he can walk to.
        val best = seeing.sortedWith(compareByDescending<BlockPos> { it.y }.thenBy { it.distSqr(origin) }).take(ROUTE_CHECKS)
            .firstOrNull { walker == null || walker.navigation.createPath(it, 0)?.canReach() == true }
        return best?.let { Point3(it.x + HALF, it.y.toDouble(), it.z + HALF) }
    }

    /** Whether a man with his eyes at [eye] sees [objective]'s centre, raised to standing height. */
    fun sees(level: Level, eye: Vec3, objective: Point3): Boolean = sees(level, eye, Vec3(objective.x, objective.y + TARGET_HEIGHT, objective.z))

    private fun sees(level: Level, eye: Vec3, target: Vec3): Boolean = DetectionSightline.visible(level, eye, target, CollisionContext.empty())

    private const val HALF = 0.5

    // The surface in the column: the highest standing spot within the world's height map there.
    private fun standing(level: Level, column: BlockPos): BlockPos? {
        val top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.x, column.z)
        return (0..STEP).map { BlockPos(column.x, top - it, column.z) }.firstOrNull { feet ->
            level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP) &&
                level.getBlockState(feet).getCollisionShape(level, feet).isEmpty &&
                level.getFluidState(feet).isEmpty &&
                level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty
        }
    }
}

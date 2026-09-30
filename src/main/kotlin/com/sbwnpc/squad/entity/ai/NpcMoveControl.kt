package com.sbwnpc.squad.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator

/**
 * Vanilla's walking, jumping where the floor ahead is really higher than a step.
 *
 * Vanilla decides to jump from the height of the next path node, which is a whole block, not from
 * the floor there: off snow six layers deep (standing at .625) toward snow four deep a block up
 * (its top at 1.375) the node is .375 up — no jump — while the snow is .75 up, too high to step.
 * The man walked into it for good. Anything that makes the floor a part of a block high does the
 * same: snow, a carpet on a step. Only a man actually walking into it jumps.
 */
class NpcMoveControl(mob: Mob) : MoveControl(mob) {
    override fun tick() {
        val moving = operation == Operation.MOVE_TO
        val x = wantedX
        val y = wantedY
        val z = wantedZ
        super.tick()
        // Only when actually held up: a stair's shape goes a whole block up, but its lower step is
        // walked up without a jump, and a man hopping up every staircase looked broken too.
        if (!moving || operation != Operation.WAIT || !mob.onGround() || mob.isInWater || !mob.horizontalCollision) return
        val dx = x - mob.x
        val dz = z - mob.z
        if (dx * dx + dz * dz >= Math.max(1.0f, mob.bbWidth).toDouble()) return
        if (floorAt(BlockPos.containing(x, y, z)) - mob.y > mob.maxUpStep()) {
            mob.jumpControl.jump()
            operation = Operation.JUMPING
        }
    }

    /** Where a man stands in the block at [pos]: on top of whatever lies there, or on the block below. */
    private fun floorAt(pos: BlockPos): Double {
        val level = mob.level()
        val shape = level.getBlockState(pos).getCollisionShape(level, pos)
        return if (shape.isEmpty) WalkNodeEvaluator.getFloorLevel(level, pos) else pos.y + shape.max(Direction.Axis.Y)
    }
}

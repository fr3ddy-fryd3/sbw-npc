package com.sbwnpc.squad.entity

import com.sbwnpc.squad.combat.DebugFlags
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3

/**
 * Stuck with somewhere to go — either walking a path without getting anywhere, or getting paths
 * that end where it stands — and what's in the way at eye height is something a hand clears in a
 * moment: leaves, a bush, glass, sand, dirt. Knock it out. Paths are planned round such blocks, but
 * not every snag is foreseen, and a man standing pressed against a hedge until the order changed
 * looked broken. Nothing harder than [SOFT_BLOCK_HARDNESS], nothing with contents, nothing at all
 * where mob griefing is off. With eye height clear, the block over its head goes instead, so it can
 * climb the one at its feet.
 */
class NpcStuckRecovery(private val npc: NpcEntity) {
    private var stuckCheckPos: Vec3? = null
    private var stuckSinceTick = -1
    /** Where it last asked to go and got a path that ends where it stands, and when — see
     *  [notePathGoesNowhere]. */
    private var nowhereToward: BlockPos? = null
    private var nowhereSinceTick = -1
    private var nowhereLastTick = -1

    /**
     * Called by the navigation for every path it plans: [goesNowhere] is a path that can't reach
     * [toward] and ends where the NPC already stands. A man boxed in like that has a path that is
     * finished before it starts — the navigation reports itself done, so from outside he looks
     * like someone who has arrived, standing still with nowhere to go.
     */
    fun notePathGoesNowhere(toward: BlockPos, goesNowhere: Boolean) {
        if (!goesNowhere) {
            nowhereSinceTick = -1
            return
        }
        if (nowhereSinceTick < 0) nowhereSinceTick = npc.tickCount
        nowhereLastTick = npc.tickCount
        nowhereToward = toward
    }

    fun tick() {
        val tick = npc.tickCount
        if (tick % STUCK_CHECK_TICKS != 0 || npc.isPassenger) return
        val here = npc.position()
        val path = npc.navigation.path
        val moved = stuckCheckPos?.let { it.distanceToSqr(here) > STUCK_MOVE_SQR } ?: true
        stuckCheckPos = here
        val walkingInPlace = path != null && !npc.navigation.isDone && !moved
        if (walkingInPlace) {
            if (stuckSinceTick < 0) stuckSinceTick = tick
        } else {
            stuckSinceTick = -1
        }
        val boxedIn = nowhereSinceTick >= 0 && tick - nowhereLastTick <= NOWHERE_FRESH_TICKS && !moved
        val toward: Vec3 = when {
            walkingInPlace && tick - stuckSinceTick >= STUCK_BREAK_TICKS -> Vec3.atCenterOf(path!!.nextNodePos)
            boxedIn && tick - nowhereSinceTick >= STUCK_BREAK_TICKS -> Vec3.atCenterOf(nowhereToward ?: return)
            else -> return
        }
        val level = npc.level() as? ServerLevel ?: return
        if (!net.neoforged.neoforge.event.EventHooks.canEntityGrief(level, npc)) return
        val dx = toward.x - npc.x
        val dz = toward.z - npc.z
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        if (len < 1.0e-3) return
        val eye = BlockPos.containing(npc.x + dx / len * 0.8, npc.eyeY, npc.z + dz / len * 0.8)
        val pos = if (solid(level, eye)) eye else eye.above()
        val state = level.getBlockState(pos)
        val hardness = state.getDestroySpeed(level, pos)
        val refusal = when {
            !solid(level, pos) -> "nothing solid there"
            !state.fluidState.isEmpty -> "fluid"
            level.getBlockEntity(pos) != null -> "has contents"
            hardness < 0f || hardness > SOFT_BLOCK_HARDNESS -> "hardness $hardness"
            state.requiresCorrectToolForDrops() -> "needs a tool"
            else -> null
        }
        // Either way, not again for another STUCK_BREAK_TICKS.
        stuckSinceTick = -1
        nowhereSinceTick = -1
        if (refusal != null) {
            DebugFlags.log("[stuck-debug] {} stuck at {}, left {} at {} ({})", npc.uuid, npc.blockPosition(), state.block.descriptionId, pos, refusal)
            return
        }
        npc.swing(InteractionHand.MAIN_HAND)
        level.destroyBlock(pos, true, npc)
        DebugFlags.log("[stuck-debug] {} broke {} at {} to get unstuck", npc.uuid, state.block.descriptionId, pos)
    }

    private fun solid(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.isAir && !state.getCollisionShape(level, pos).isEmpty
    }

    private companion object {
        const val STUCK_CHECK_TICKS = 10
        const val STUCK_MOVE_SQR = 0.15 * 0.15
        /** Stood still this long with a path before it clears the way. */
        const val STUCK_BREAK_TICKS = 30
        /** A path that goes nowhere counts only while they keep coming. */
        const val NOWHERE_FRESH_TICKS = 40
        /** Anything a hand clears quickly: leaves 0.2, glass 0.3, sand and dirt 0.5, gravel and grass
         *  0.6. Stone (1.5) and planks (2) stay. */
        const val SOFT_BLOCK_HARDNESS = 0.6f
    }
}

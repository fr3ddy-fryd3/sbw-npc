package com.sbwnpc.squad.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Wooden trapdoors over a ladder or scaffolding shaft: the lid of a tower hatch (#177). The path
 * finders count one as part of the shaft ([isTrapdoor] on a cell above a climbable one), and the
 * navigation opens it as the man reaches it and shuts it behind him ([open], [closeBehind]).
 * Iron trapdoors stay closed: they need redstone.
 */
object Hatches {
    /** How close the man has to be to the hatch for it to open under him. */
    private const val REACH_SQR = 2.5 * 2.5

    private const val PITCH_SPREAD = 0.1f
    private const val PITCH_BASE = 0.9f

    /** How far he has to be from a hatch he opened before it is shut again. */
    private const val CLOSE_DISTANCE_SQR = 3.5 * 3.5

    fun isTrapdoor(state: BlockState): Boolean = state.`is`(BlockTags.WOODEN_TRAPDOORS)

    /** Opens the closed hatch at [pos] if [mob] is within reach of it; true when this call opened it. */
    fun open(mob: Mob, pos: BlockPos): Boolean {
        val level = mob.level()
        val state = level.getBlockState(pos)
        if (!isTrapdoor(state) || state.getValue(TrapDoorBlock.OPEN)) return false
        if (mob.position().distanceToSqr(Vec3.atCenterOf(pos)) > REACH_SQR) return false
        set(level, pos, state, true)
        return true
    }

    /** Shuts the hatch at [pos] again once [mob] has moved away from it. */
    fun closeBehind(mob: Mob, pos: BlockPos): Boolean {
        val level = mob.level()
        val state = level.getBlockState(pos)
        if (!isTrapdoor(state) || !state.getValue(TrapDoorBlock.OPEN)) return true
        if (mob.position().distanceToSqr(Vec3.atCenterOf(pos)) < CLOSE_DISTANCE_SQR) return false
        set(level, pos, state, false)
        return true
    }

    private fun set(level: Level, pos: BlockPos, state: BlockState, open: Boolean) {
        level.setBlock(pos, state.setValue(TrapDoorBlock.OPEN, open), 2)
        level.playSound(null, pos, if (open) SoundEvents.WOODEN_TRAPDOOR_OPEN else SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1f, level.random.nextFloat() * PITCH_SPREAD + PITCH_BASE)
    }
}

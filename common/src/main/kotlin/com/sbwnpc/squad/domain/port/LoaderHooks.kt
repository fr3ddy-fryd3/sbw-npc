package com.sbwnpc.squad.domain.port

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.state.BlockState

/**
 * What the mod loader adds to vanilla that gameplay relies on: each call keeps the loader's own
 * semantics (its events, its saved data), so other mods hooking them still apply.
 */
interface LoaderHooks {
    /** Whether [entity] may break or change blocks in [level], after the loader's mob-griefing hook. */
    fun canGrief(level: ServerLevel, entity: Entity): Boolean

    /** The loader's extra saved data on [entity], written with the entity and kept across reloads. */
    fun persistentData(entity: Entity): CompoundTag

    /** Whether [state] at [pos] is climbable for [entity], as the loader decides, other mods' ladders included. */
    fun isLadder(state: BlockState, level: LevelReader, pos: BlockPos, entity: LivingEntity): Boolean
}

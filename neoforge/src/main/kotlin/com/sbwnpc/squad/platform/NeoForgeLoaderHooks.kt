package com.sbwnpc.squad.platform

import com.sbwnpc.squad.domain.port.LoaderHooks
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.event.EventHooks

/** [LoaderHooks] on NeoForge's entity, block state and event extensions. */
object NeoForgeLoaderHooks : LoaderHooks {
    override fun canGrief(level: ServerLevel, entity: Entity): Boolean = EventHooks.canEntityGrief(level, entity)

    override fun persistentData(entity: Entity): CompoundTag = entity.persistentData

    override fun isLadder(state: BlockState, level: LevelReader, pos: BlockPos, entity: LivingEntity): Boolean = state.isLadder(level, pos, entity)
}

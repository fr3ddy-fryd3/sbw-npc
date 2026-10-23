package com.sbwnpc.squad.platform

import com.mojang.serialization.Codec
import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.domain.port.LoaderHooks
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.state.BlockState

/** [LoaderHooks] on vanilla rules and Fabric's data attachments. */
object FabricLoaderHooks : LoaderHooks {
    /** The counterpart of NeoForge's per-entity persistent data: a compound saved with the entity. */
    private val DATA: AttachmentType<CompoundTag> =
        AttachmentRegistry.createPersistent(ModIdentity.loc("persistent_data"), CompoundTag.CODEC as Codec<CompoundTag>)

    override fun canGrief(level: ServerLevel, entity: Entity): Boolean = level.gameRules.getBoolean(GameRules.RULE_MOBGRIEFING)

    override fun persistentData(entity: Entity): CompoundTag = entity.getAttachedOrCreate(DATA) { CompoundTag() }

    override fun isLadder(state: BlockState, level: LevelReader, pos: BlockPos, entity: LivingEntity): Boolean = state.`is`(BlockTags.CLIMBABLE)
}

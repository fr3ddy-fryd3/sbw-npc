package com.sbwnpc.squad.compat

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.storage.DimensionDataStorage
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.core.BlockPos
import com.mojang.serialization.MapCodec

/*
 * Base classes whose overridable methods differ between Minecraft versions. The shared classes
 * extend these and implement the version-free hooks; Forge on 1.20.1 has its own versions of these
 * names. This file is the 1.21 one (NeoForge and Fabric).
 */

/** [SavedData] written through [write], whatever the version's `save` signature. */
abstract class VersionedSavedData : SavedData() {
    abstract fun write(tag: CompoundTag): CompoundTag

    final override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = write(tag)
}

object SavedDataAccess {
    /** The saved data called [name], created with [create] or read with [load] when the world has none yet. */
    fun <T : VersionedSavedData> fetch(storage: DimensionDataStorage, name: String, create: () -> T, load: (CompoundTag) -> T): T =
        storage.computeIfAbsent(SavedData.Factory(create, { tag, _ -> load(tag) }, null), name)
}

/** A block with a block entity, used through [onUse] (right-click with any item). */
abstract class VersionedEntityBlock(properties: Properties) : BaseEntityBlock(properties) {
    abstract fun onUse(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult

    // Block types are a data-driven registry on 1.21; nothing here is ever built from one.
    final override fun codec(): MapCodec<out BaseEntityBlock> = MapCodec.unit(this)

    final override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult =
        onUse(state, level, pos, player, hitResult)
}

/** A block entity saved through [writeData] and [readData]. */
abstract class VersionedBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : BlockEntity(type, pos, state) {
    protected abstract fun writeData(tag: CompoundTag)

    protected abstract fun readData(tag: CompoundTag)

    final override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        writeData(tag)
    }

    final override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        readData(tag)
    }
}

/** An item whose tooltip lines come from [addTooltip]. */
abstract class VersionedItem(properties: Properties) : Item(properties) {
    protected abstract fun addTooltip(stack: ItemStack, tooltip: MutableList<Component>)

    final override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        addTooltip(stack, tooltip)
    }
}

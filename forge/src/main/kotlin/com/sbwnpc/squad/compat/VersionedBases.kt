package com.sbwnpc.squad.compat

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.level.storage.DimensionDataStorage
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext

/*
 * Base classes whose overridable methods differ between Minecraft versions. The shared classes
 * extend these and implement the version-free hooks. This file is the 1.20.1 one (Forge);
 * NeoForge and Fabric on 1.21 have their own versions of these names.
 */

/** [SavedData] written through [write], whatever the version's `save` signature. */
abstract class VersionedSavedData : SavedData() {
    abstract fun write(tag: CompoundTag): CompoundTag

    final override fun save(tag: CompoundTag): CompoundTag = write(tag)
}

object SavedDataAccess {
    /** The saved data called [name], created with [create] or read with [load] when the world has none yet. */
    fun <T : VersionedSavedData> fetch(storage: DimensionDataStorage, name: String, create: () -> T, load: (CompoundTag) -> T): T =
        storage.computeIfAbsent({ tag -> load(tag) }, { create() }, name)
}

/** A block with a block entity, used through [onUse] (right-click with any item). */
abstract class VersionedEntityBlock(properties: Properties) : BaseEntityBlock(properties) {
    abstract fun onUse(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult

    @Deprecated("Deprecated in Java")
    final override fun use(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): InteractionResult =
        onUse(state, level, pos, player, hit)
}

/** A block entity saved through [writeData] and [readData]. */
abstract class VersionedBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : BlockEntity(type, pos, state) {
    protected abstract fun writeData(tag: CompoundTag)

    protected abstract fun readData(tag: CompoundTag)

    final override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        writeData(tag)
    }

    final override fun load(tag: CompoundTag) {
        super.load(tag)
        readData(tag)
    }
}

/** An item whose tooltip lines come from [addTooltip]. */
abstract class VersionedItem(properties: Properties) : Item(properties) {
    protected abstract fun addTooltip(stack: ItemStack, tooltip: MutableList<Component>)

    final override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        addTooltip(stack, tooltip)
    }
}

/**
 * A [ClipContext] that hits collision shapes (no fluids) as seen by [collision]. 1.20.1's context
 * takes an entity rather than a collision context, so the shape is looked up here.
 */
open class VersionedClipContext(from: Vec3, to: Vec3, private val collision: CollisionContext) :
    ClipContext(from, to, Block.COLLIDER, Fluid.NONE, null as Entity?) {
    override fun getBlockShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape = Block.COLLIDER.get(state, level, pos, collision)
}

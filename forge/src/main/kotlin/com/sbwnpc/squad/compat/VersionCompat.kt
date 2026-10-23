package com.sbwnpc.squad.compat

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack

/** Small calls whose names or shapes differ between Minecraft versions. This is the 1.20.1 file; NeoForge and Fabric have their own. */
object VersionCompat {
    /** The block position stored under [key], or null when there is none. */
    fun readBlockPos(tag: CompoundTag, key: String): BlockPos? =
        if (tag.contains(key, Tag.TAG_COMPOUND.toInt())) NbtUtils.readBlockPos(tag.getCompound(key)) else null

    /** [stack] written for saving; 1.20.1 needs no registries, so [owner] is not used. */
    @Suppress("UNUSED_PARAMETER")
    fun saveStack(owner: Entity, stack: ItemStack): Tag = stack.save(CompoundTag())

    @Suppress("UNUSED_PARAMETER")
    fun loadStack(owner: Entity, tag: CompoundTag): ItemStack = ItemStack.of(tag)
}

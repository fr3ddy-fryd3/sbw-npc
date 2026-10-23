package com.sbwnpc.squad.compat

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack

/** Small calls whose names or shapes differ between Minecraft versions. This is the 1.21 file; Forge on 1.20.1 has its own. */
object VersionCompat {
    /** The block position stored under [key], or null when there is none. */
    fun readBlockPos(tag: CompoundTag, key: String): BlockPos? = NbtUtils.readBlockPos(tag, key).orElse(null)

    /** [stack] written for saving with [owner], whose world supplies the registries on 1.21. */
    fun saveStack(owner: Entity, stack: ItemStack): Tag = stack.save(owner.registryAccess())

    fun loadStack(owner: Entity, tag: CompoundTag): ItemStack = ItemStack.parseOptional(owner.registryAccess(), tag)
}

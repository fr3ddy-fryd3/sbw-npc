package com.sbwnpc.squad.util

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/** A stack's free-form NBT (the vanilla `custom_data` component). */
object StackData {
    /** A copy: changing it does not change the stack. */
    fun read(stack: ItemStack): CompoundTag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()

    /** Merges whatever [write] puts into a fresh tag over the stack's existing data. */
    fun update(stack: ItemStack, write: (CompoundTag) -> Unit) {
        val changes = CompoundTag().also(write)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(read(stack).merge(changes)))
    }
}

package com.sbwnpc.squad.util

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/** A stack's free-form NBT (its root tag on 1.20.1). */
object StackData {
    /** A copy: changing it does not change the stack. */
    fun read(stack: ItemStack): CompoundTag = stack.tag?.copy() ?: CompoundTag()

    /** Merges whatever [write] puts into a fresh tag over the stack's existing data. */
    fun update(stack: ItemStack, write: (CompoundTag) -> Unit) {
        val changes = CompoundTag().also(write)
        stack.tag = read(stack).merge(changes)
    }
}

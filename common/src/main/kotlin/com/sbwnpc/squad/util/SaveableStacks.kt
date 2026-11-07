package com.sbwnpc.squad.util

import net.minecraft.world.item.ItemStack

/**
 * Vanilla 1.21's item stack codec accepts counts of 1 to 99 only: saving a larger stack throws and
 * takes the world save with it. NeoForge lifts that limit, so a stack of 100 heavy rounds went
 * unnoticed there and crashed Fabric when the chunk holding the dropped stack was saved.
 */
object SaveableStacks {
    const val MAX_COUNT = 99

    /** [stack] itself when it is small enough to save, else a copy of [MAX_COUNT]. */
    fun capped(stack: ItemStack): ItemStack = if (stack.count <= MAX_COUNT) stack else stack.copyWithCount(MAX_COUNT)
}

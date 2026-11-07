package com.sbwnpc.squad.util

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SaveableStacksTest {
    @Test
    fun `a stack of 100 is cut to the 99 the vanilla codec saves`() {
        assertEquals(SaveableStacks.MAX_COUNT, SaveableStacks.capped(ItemStack(Items.ARROW, 100)).count)
    }

    @Test
    fun `a stack that can be saved is returned as it is`() {
        val stack = ItemStack(Items.ARROW, SaveableStacks.MAX_COUNT)
        assertSame(stack, SaveableStacks.capped(stack))
    }

    @Test
    fun `capping does not touch the stack it was given`() {
        val stack = ItemStack(Items.ARROW, 100)
        SaveableStacks.capped(stack)
        assertEquals(100, stack.count)
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}

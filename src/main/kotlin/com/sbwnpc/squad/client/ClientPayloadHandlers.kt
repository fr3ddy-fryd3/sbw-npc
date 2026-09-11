package com.sbwnpc.squad.client

import com.sbwnpc.squad.client.screen.CommandScreen
import com.sbwnpc.squad.client.screen.RecruitScreen
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * Client-only entry points. Kept free of `Minecraft`-typed fields so the class can be referenced
 * from common code without classloading client internals — `Minecraft` is only touched inside the
 * method bodies, which never run server-side.
 */
object ClientPayloadHandlers {

    fun openRecruitScreen(stack: ItemStack) {
        net.minecraft.client.Minecraft.getInstance().setScreen(RecruitScreen(stack))
    }

    fun openCommandScreen(snapshot: CompoundTag) {
        net.minecraft.client.Minecraft.getInstance().setScreen(CommandScreen(snapshot))
    }

    fun openHud(snapshot: CompoundTag) {
        HudOverlayState.onSnapshot(snapshot)
    }
}

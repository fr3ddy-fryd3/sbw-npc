package com.sbwnpc.squad.client

import com.sbwnpc.squad.client.screen.ChooseFactionScreen
import com.sbwnpc.squad.client.screen.CommandScreen
import com.sbwnpc.squad.client.screen.FinishRouteScreen
import com.sbwnpc.squad.client.screen.RecruitScreen
import com.sbwnpc.squad.client.screen.RoutesScreen
import com.sbwnpc.squad.item.SquadToolItem
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
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

    /** Recruit-mode air-click round-trips through the server first (faction-lock check); this
     *  reopens using whichever hand is still holding the tool by the time the reply arrives. */
    fun openRecruitScreenFromHeldItem() {
        val player = net.minecraft.client.Minecraft.getInstance().player ?: return
        val stack = listOf(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)
            .map { player.getItemInHand(it) }
            .firstOrNull { it.item is SquadToolItem }
            ?: return
        openRecruitScreen(stack)
    }

    fun openFactionPick() {
        net.minecraft.client.Minecraft.getInstance().setScreen(ChooseFactionScreen())
    }

    fun openCommandScreen(snapshot: CompoundTag) {
        net.minecraft.client.Minecraft.getInstance().setScreen(CommandScreen(snapshot))
    }

    fun openHud(snapshot: CompoundTag) {
        HudOverlayState.onSnapshot(snapshot)
    }

    fun openRoutesScreen(snapshot: CompoundTag) {
        net.minecraft.client.Minecraft.getInstance().setScreen(RoutesScreen(snapshot))
    }

    fun openFinishRoute(pointCount: Int) {
        net.minecraft.client.Minecraft.getInstance().setScreen(FinishRouteScreen(pointCount))
    }
}

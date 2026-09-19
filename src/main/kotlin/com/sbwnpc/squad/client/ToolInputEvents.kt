package com.sbwnpc.squad.client

import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.ToggleToolModePayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Ctrl+air-click mode switch for [SquadToolItem]. A raw ctrl-modifier isn't part of synced player
 * input the way sneaking is — the server has no way to know it was held — so this intercepts the
 * click at the input level, client-side only, before it ever becomes an [SquadToolItem.use] call,
 * and tells the server what happened over [ToggleToolModePayload] instead. Cancelling here stops
 * the normal right-click (open GUI / deploy) from firing too — see
 * [InputEvent.InteractionKeyMappingTriggered]'s own contract.
 */
@EventBusSubscriber(Dist.CLIENT)
object ToolInputEvents {
    @SubscribeEvent
    fun onInteractionKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem || !Screen.hasControlDown()) return
        val player = Minecraft.getInstance().player ?: return
        if (player.getItemInHand(event.hand).item !is SquadToolItem) return
        event.isCanceled = true
        event.setSwingHand(false)
        PacketDistributor.sendToServer(ToggleToolModePayload)
    }
}

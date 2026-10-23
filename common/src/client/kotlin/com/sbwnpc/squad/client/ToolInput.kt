package com.sbwnpc.squad.client

import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.ToggleToolModePayload
import com.sbwnpc.squad.network.sendToServer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.InteractionHand

/**
 * Ctrl+air-click mode switch for [SquadToolItem]. A raw ctrl-modifier isn't part of synced player
 * input the way sneaking is — the server has no way to know it was held — so the loader calls this
 * at the input level, client-side only, before the click becomes an [SquadToolItem.use] call, and
 * the server is told over [ToggleToolModePayload] instead.
 */
object ToolInput {
    /** Handles a use-item click; true means it was a mode switch and the click must not go on to use the item. */
    fun toggleModeOnControlClick(hand: InteractionHand): Boolean {
        if (!Screen.hasControlDown()) return false
        val player = Minecraft.getInstance().player ?: return false
        if (player.getItemInHand(hand).item !is SquadToolItem) return false
        sendToServer(ToggleToolModePayload)
        return true
    }
}

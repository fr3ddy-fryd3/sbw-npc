package com.sbwnpc.squad.item

import com.sbwnpc.squad.init.ModItems
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent

/**
 * Hands a survival player the Squad Command Tool when they have none on them — it has no recipe,
 * and without it there is no way to deploy or command anything. Checked on login and on respawn,
 * so a tool lost with everything else on death comes back. Creative has it in the tab.
 */
@EventBusSubscriber
object SquadToolGrant {
    @SubscribeEvent
    fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) = grant(event.entity as? ServerPlayer)

    @SubscribeEvent
    fun onRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        if (!event.isEndConquered) grant(event.entity as? ServerPlayer)
    }

    private fun grant(player: ServerPlayer?) {
        if (player == null || player.isCreative || player.isSpectator) return
        val tool = ModItems.SQUAD_TOOL.get()
        if (player.inventory.contains { it.`is`(tool) }) return
        if (!player.inventory.add(ItemStack(tool))) player.drop(ItemStack(tool), false)
    }
}

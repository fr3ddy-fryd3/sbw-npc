package com.sbwnpc.squad.client

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext

/** NeoForge's client events for the quick-command HUD ([HudClient], [HudKeys]). */
@EventBusSubscriber(Dist.CLIENT)
object HudClientEvents {
    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) = HudClient.onClientTick()

    @SubscribeEvent
    fun onRenderGui(event: RenderGuiEvent.Post) = HudClient.onRenderGui(event.guiGraphics)

    @SubscribeEvent
    fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        HudKeys.KEYS.forEach {
            it.keyConflictContext = KeyConflictContext.IN_GAME
            event.register(it)
        }
    }
}

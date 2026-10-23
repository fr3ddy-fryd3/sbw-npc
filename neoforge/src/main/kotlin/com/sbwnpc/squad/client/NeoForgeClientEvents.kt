package com.sbwnpc.squad.client

import com.sbwnpc.squad.util.PerfProbe
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import net.neoforged.neoforge.client.event.RenderLivingEvent
import net.neoforged.neoforge.client.event.ScreenEvent

/** NeoForge's client events, handed to the loader-free client state and probes. */
@EventBusSubscriber(Dist.CLIENT)
object NeoForgeClientEvents {
    @SubscribeEvent
    fun onRenderScreen(event: ScreenEvent.Render.Post) = FactionBanner.render(event.screen, event.guiGraphics)

    @SubscribeEvent
    fun onLogin(event: ClientPlayerNetworkEvent.LoggingIn) = MapState.onLogin()

    @SubscribeEvent
    fun onLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        FactionBanner.onLogout()
        MapState.onLogout()
    }

    @SubscribeEvent
    fun onRenderNpc(event: RenderLivingEvent.Pre<*, *>) = PerfProbe.Client.onRenderNpc(event.entity)

    @SubscribeEvent
    fun onRenderGui(event: RenderGuiEvent.Post) = PerfProbe.Client.onRenderGui()
}

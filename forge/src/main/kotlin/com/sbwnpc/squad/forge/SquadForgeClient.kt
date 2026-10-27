package com.sbwnpc.squad.forge

import com.sbwnpc.squad.client.FactionBanner
import com.sbwnpc.squad.client.HudClient
import com.sbwnpc.squad.client.HudKeys
import com.sbwnpc.squad.client.MapState
import com.sbwnpc.squad.client.NpcModel
import com.sbwnpc.squad.client.ToolInput
import com.sbwnpc.squad.client.renderer.NpcRenderer
import com.sbwnpc.squad.init.ForgeEntities
import com.sbwnpc.squad.integration.sbw.client.SbwClientPorts
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.IEventBus
import java.util.function.Consumer

/** The client side of the Forge entry point: renderers, keys, the HUD and the screens' banner. Only ever called on a client. */
object SquadForgeClient {
    fun register(modBus: IEventBus) {
        SbwClientPorts.install(modBus)

        modBus.addListener(Consumer<EntityRenderersEvent.RegisterLayerDefinitions> { it.registerLayerDefinition(NpcModel.LAYER, NpcModel::createBodyLayer) })
        modBus.addListener(Consumer<EntityRenderersEvent.RegisterRenderers> { it.registerEntityRenderer(ForgeEntities.NPC.get(), ::NpcRenderer) })
        modBus.addListener(Consumer<RegisterKeyMappingsEvent> { event -> HudKeys.KEYS.forEach { event.register(it) } })

        val bus = MinecraftForge.EVENT_BUS
        bus.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent::class.java, Consumer { event ->
            if (event.phase == TickEvent.Phase.END) HudClient.onClientTick()
        })
        bus.addListener(EventPriority.NORMAL, false, RenderGuiEvent.Post::class.java, Consumer { HudClient.onRenderGui(it.guiGraphics) })
        bus.addListener(EventPriority.NORMAL, false, ScreenEvent.Render.Post::class.java, Consumer { FactionBanner.render(it.screen, it.guiGraphics) })
        bus.addListener(EventPriority.NORMAL, false, net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn::class.java, Consumer { MapState.onLogin() })
        bus.addListener(EventPriority.NORMAL, false, net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut::class.java, Consumer {
            FactionBanner.onLogout()
            MapState.onLogout()
        })
        // Ctrl+air-click is a mode switch for the squad tool, not a use of it: cancelling stops the normal right-click.
        bus.addListener(EventPriority.NORMAL, false, InputEvent.InteractionKeyMappingTriggered::class.java, Consumer { event ->
            if (!event.isUseItem || !ToolInput.toggleModeOnControlClick(event.hand)) return@Consumer
            event.isCanceled = true
            event.setSwingHand(false)
        })
    }
}

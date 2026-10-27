package com.sbwnpc.squad.client

import com.sbwnpc.squad.client.renderer.NpcRenderer
import com.sbwnpc.squad.domain.port.ClientPorts
import com.sbwnpc.squad.init.FabricEntities
import com.sbwnpc.squad.integration.sbw.client.SbwClientPorts
import com.sbwnpc.squad.network.BarracksQueuePayload
import com.sbwnpc.squad.network.FabricServerboundTransport
import com.sbwnpc.squad.network.MapFeedPayload
import com.sbwnpc.squad.network.OpenBarracksScreenPayload
import com.sbwnpc.squad.network.OpenCommandScreenPayload
import com.sbwnpc.squad.network.OpenDiplomacyPayload
import com.sbwnpc.squad.network.OpenFactionPickPayload
import com.sbwnpc.squad.network.OpenFinishRoutePayload
import com.sbwnpc.squad.network.OpenHudPayload
import com.sbwnpc.squad.network.OpenRecruitScreenPayload
import com.sbwnpc.squad.network.OpenRoutesScreenPayload
import com.sbwnpc.squad.network.OpenSupplyScreenPayload
import com.sbwnpc.squad.network.PlayerFactionPayload
import com.sbwnpc.squad.network.ServerboundPackets
import com.sbwnpc.squad.network.SquadDetailsPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Fabric client entry point: renderers, keys, the HUD, and the handlers of the packets the server sends. */
class SquadFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        ServerboundPackets.transport = FabricServerboundTransport
        SbwClientPorts.install()

        EntityModelLayerRegistry.registerModelLayer(NpcModel.LAYER, NpcModel::createBodyLayer)
        EntityRendererRegistry.register(FabricEntities.NPC, ::NpcRenderer)

        HudKeys.KEYS.forEach { KeyBindingHelper.registerKeyBinding(it) }
        ClientTickEvents.END_CLIENT_TICK.register { HudClient.onClientTick() }
        HudRenderCallback.EVENT.register { graphics, _ -> HudClient.onRenderGui(graphics) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterRender(screen).register { s, graphics, _, _, _ -> FactionBanner.render(s, graphics) }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> MapState.onLogin() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            FactionBanner.onLogout()
            MapState.onLogout()
        }

        receive(OpenCommandScreenPayload.TYPE) { ClientPayloadHandlers.openCommandScreen(it.data) }
        receive(SquadDetailsPayload.TYPE) { ClientPayloadHandlers.squadDetails(it.data) }
        receive(OpenHudPayload.TYPE) { ClientPayloadHandlers.openHud(it.data) }
        receive(OpenFactionPickPayload.TYPE) { ClientPayloadHandlers.openFactionPick() }
        receive(PlayerFactionPayload.TYPE) { ClientPayloadHandlers.playerFaction(it.faction) }
        receive(OpenRecruitScreenPayload.TYPE) { ClientPayloadHandlers.openRecruitScreenFromHeldItem() }
        receive(OpenRoutesScreenPayload.TYPE) { ClientPayloadHandlers.openRoutesScreen(it.data) }
        receive(OpenFinishRoutePayload.TYPE) { ClientPayloadHandlers.openFinishRoute(it.pointCount) }
        receive(MapFeedPayload.TYPE) { ClientPayloadHandlers.mapFeed(it.data) }
        receive(OpenDiplomacyPayload.TYPE) { ClientPayloadHandlers.openDiplomacyScreen(it.data) }
        receive(OpenBarracksScreenPayload.TYPE) { ClientPayloadHandlers.openBarracksScreen(it.pos, it.config) }
        receive(BarracksQueuePayload.TYPE) { ClientPayloadHandlers.barracksQueue(it.pos, it.data) }
        receive(OpenSupplyScreenPayload.TYPE) { ClientPayloadHandlers.openSupplyScreen(it.pos, it.data) }
    }

    /** Runs [handler] on the client thread: Fabric delivers packets on the network thread. */
    private fun <T : CustomPacketPayload> receive(type: CustomPacketPayload.Type<T>, handler: (T) -> Unit) {
        ClientPlayNetworking.registerGlobalReceiver(type) { payload, _ -> Minecraft.getInstance().execute { handler(payload) } }
    }
}

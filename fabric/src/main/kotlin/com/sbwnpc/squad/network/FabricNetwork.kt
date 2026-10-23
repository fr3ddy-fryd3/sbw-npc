package com.sbwnpc.squad.network

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.entity.player.Player

/** [PayloadContext] over a Fabric server-bound packet. */
private class FabricPayloadContext(private val context: ServerPlayNetworking.Context) : PayloadContext {
    override fun player(): Player = context.player()
    override fun enqueueWork(task: Runnable) {
        context.server().execute(task)
    }
}

/** Registers the packets and their server-side handlers; the clientbound handlers are the client's own. */
object FabricNetwork {
    private fun <T : CustomPacketPayload> toServer(
        type: CustomPacketPayload.Type<T>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, T>,
        handler: (T, PayloadContext) -> Unit,
    ) {
        PayloadTypeRegistry.playC2S().register(type, codec)
        ServerPlayNetworking.registerGlobalReceiver(type) { payload, context -> handler(payload, FabricPayloadContext(context)) }
    }

    /** Both sides must know a clientbound type; the server only sends it. */
    fun <T : CustomPacketPayload> toClient(type: CustomPacketPayload.Type<T>, codec: StreamCodec<in RegistryFriendlyByteBuf, T>) {
        PayloadTypeRegistry.playS2C().register(type, codec)
    }

    fun register() {
        toServer(ConfigureToolPayload.TYPE, ConfigureToolPayload.CODEC, ServerPayloadHandlers::onConfigureTool)
        toServer(ToggleToolModePayload.TYPE, ToggleToolModePayload.CODEC) { _, ctx -> ServerPayloadHandlers.onToggleToolMode(ctx) }
        toServer(SquadCmdPayload.TYPE, SquadCmdPayload.CODEC, ServerPayloadHandlers::onSquadCmd)
        toServer(RequestHudPayload.TYPE, RequestHudPayload.CODEC) { _, ctx -> ServerPayloadHandlers.onRequestHud(ctx) }
        toServer(HudOrderPayload.TYPE, HudOrderPayload.CODEC, ServerPayloadHandlers::onHudOrder)
        toServer(HudOrderAllPayload.TYPE, HudOrderAllPayload.CODEC, ServerPayloadHandlers::onHudOrderAll)
        toServer(ChooseFactionPayload.TYPE, ChooseFactionPayload.CODEC, ServerPayloadHandlers::onChooseFaction)
        toServer(RouteCmdPayload.TYPE, RouteCmdPayload.CODEC, ServerPayloadHandlers::onRouteCmd)
        toServer(ConfigureBarracksPayload.TYPE, ConfigureBarracksPayload.CODEC, ServerPayloadHandlers::onConfigureBarracks)
        toServer(RequestBarracksQueuePayload.TYPE, RequestBarracksQueuePayload.CODEC, ServerPayloadHandlers::onRequestBarracksQueue)
        toServer(DiplomacyCmdPayload.TYPE, DiplomacyCmdPayload.CODEC, ServerPayloadHandlers::onDiplomacyCmd)
        toServer(SupplyCmdPayload.TYPE, SupplyCmdPayload.CODEC, ServerPayloadHandlers::onSupplyCmd)
        toServer(MapSubscribePayload.TYPE, MapSubscribePayload.CODEC) { _, ctx -> ServerPayloadHandlers.onMapSubscribe(ctx) }

        toClient(OpenCommandScreenPayload.TYPE, OpenCommandScreenPayload.CODEC)
        toClient(SquadDetailsPayload.TYPE, SquadDetailsPayload.CODEC)
        toClient(OpenHudPayload.TYPE, OpenHudPayload.CODEC)
        toClient(OpenFactionPickPayload.TYPE, OpenFactionPickPayload.CODEC)
        toClient(PlayerFactionPayload.TYPE, PlayerFactionPayload.CODEC)
        toClient(OpenRecruitScreenPayload.TYPE, OpenRecruitScreenPayload.CODEC)
        toClient(OpenRoutesScreenPayload.TYPE, OpenRoutesScreenPayload.CODEC)
        toClient(OpenFinishRoutePayload.TYPE, OpenFinishRoutePayload.CODEC)
        toClient(MapFeedPayload.TYPE, MapFeedPayload.CODEC)
        toClient(OpenDiplomacyPayload.TYPE, OpenDiplomacyPayload.CODEC)
        toClient(OpenBarracksScreenPayload.TYPE, OpenBarracksScreenPayload.CODEC)
        toClient(BarracksQueuePayload.TYPE, BarracksQueuePayload.CODEC)
        toClient(OpenSupplyScreenPayload.TYPE, OpenSupplyScreenPayload.CODEC)
    }
}

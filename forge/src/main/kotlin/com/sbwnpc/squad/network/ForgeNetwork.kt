package com.sbwnpc.squad.network

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.client.ClientPayloadHandlers
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel

/** [PayloadContext] over a Forge server-bound packet, already on the server thread. */
private class ForgePayloadContext(private val sender: ServerPlayer) : PayloadContext {
    override fun player(): Player = sender

    override fun enqueueWork(task: Runnable) = task.run()
}

/** Forge's channel: the one place packets are registered, sent and delivered. */
object ForgeNetwork : ClientboundTransport, ServerboundTransport {
    private const val PROTOCOL = "2"
    private var nextId = 0

    private val CHANNEL: SimpleChannel = NetworkRegistry.newSimpleChannel(ModIdentity.loc("main"), { PROTOCOL }, PROTOCOL::equals, PROTOCOL::equals)

    override fun send(player: ServerPlayer, payload: PacketPayload) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, payload)
    }

    override fun send(payload: PacketPayload) {
        CHANNEL.sendToServer(payload)
    }

    private fun <T : PacketPayload> toServer(type: Class<T>, codec: PayloadCodec<T>, handler: (T, PayloadContext) -> Unit) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_SERVER)
            .encoder { packet, buf -> codec.encode(buf, packet) }
            .decoder { buf -> codec.decode(buf) }
            .consumerMainThread { packet, context ->
                val sender = context.get().sender
                if (sender != null) handler(packet, ForgePayloadContext(sender))
            }
            .add()
    }

    /** The client's handler runs only on a client: the classes it names do not exist on a server. */
    private fun <T : PacketPayload> toClient(type: Class<T>, codec: PayloadCodec<T>, handler: (T) -> Unit) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder { packet, buf -> codec.encode(buf, packet) }
            .decoder { buf -> codec.decode(buf) }
            .consumerMainThread { packet, _: java.util.function.Supplier<NetworkEvent.Context> ->
                if (FMLEnvironment.dist == Dist.CLIENT) handler(packet)
            }
            .add()
    }

    fun register() {
        toServer(ConfigureToolPayload::class.java, ConfigureToolPayload.CODEC, ServerPayloadHandlers::onConfigureTool)
        toServer(ToggleToolModePayload::class.java, ToggleToolModePayload.CODEC) { _, ctx -> ServerPayloadHandlers.onToggleToolMode(ctx) }
        toServer(SquadCmdPayload::class.java, SquadCmdPayload.CODEC, ServerPayloadHandlers::onSquadCmd)
        toServer(RequestHudPayload::class.java, RequestHudPayload.CODEC) { _, ctx -> ServerPayloadHandlers.onRequestHud(ctx) }
        toServer(HudOrderPayload::class.java, HudOrderPayload.CODEC, ServerPayloadHandlers::onHudOrder)
        toServer(HudOrderAllPayload::class.java, HudOrderAllPayload.CODEC, ServerPayloadHandlers::onHudOrderAll)
        toServer(ChooseFactionPayload::class.java, ChooseFactionPayload.CODEC, ServerPayloadHandlers::onChooseFaction)
        toServer(RouteCmdPayload::class.java, RouteCmdPayload.CODEC, ServerPayloadHandlers::onRouteCmd)
        toServer(ConfigureBarracksPayload::class.java, ConfigureBarracksPayload.CODEC, ServerPayloadHandlers::onConfigureBarracks)
        toServer(RequestBarracksQueuePayload::class.java, RequestBarracksQueuePayload.CODEC, ServerPayloadHandlers::onRequestBarracksQueue)
        toServer(DiplomacyCmdPayload::class.java, DiplomacyCmdPayload.CODEC, ServerPayloadHandlers::onDiplomacyCmd)
        toServer(SupplyCmdPayload::class.java, SupplyCmdPayload.CODEC, ServerPayloadHandlers::onSupplyCmd)
        toServer(MapSubscribePayload::class.java, MapSubscribePayload.CODEC) { _, ctx -> ServerPayloadHandlers.onMapSubscribe(ctx) }

        toClient(OpenCommandScreenPayload::class.java, OpenCommandScreenPayload.CODEC) { ClientPayloadHandlers.openCommandScreen(it.data) }
        toClient(SquadDetailsPayload::class.java, SquadDetailsPayload.CODEC) { ClientPayloadHandlers.squadDetails(it.data) }
        toClient(OpenHudPayload::class.java, OpenHudPayload.CODEC) { ClientPayloadHandlers.openHud(it.data) }
        toClient(OpenFactionPickPayload::class.java, OpenFactionPickPayload.CODEC) { ClientPayloadHandlers.openFactionPick() }
        toClient(PlayerFactionPayload::class.java, PlayerFactionPayload.CODEC) { ClientPayloadHandlers.playerFaction(it.faction) }
        toClient(OpenRecruitScreenPayload::class.java, OpenRecruitScreenPayload.CODEC) { ClientPayloadHandlers.openRecruitScreenFromHeldItem() }
        toClient(OpenRoutesScreenPayload::class.java, OpenRoutesScreenPayload.CODEC) { ClientPayloadHandlers.openRoutesScreen(it.data) }
        toClient(OpenFinishRoutePayload::class.java, OpenFinishRoutePayload.CODEC) { ClientPayloadHandlers.openFinishRoute(it.pointCount) }
        toClient(MapFeedPayload::class.java, MapFeedPayload.CODEC) { ClientPayloadHandlers.mapFeed(it.data) }
        toClient(OpenDiplomacyPayload::class.java, OpenDiplomacyPayload.CODEC) { ClientPayloadHandlers.openDiplomacyScreen(it.data) }
        toClient(OpenBarracksScreenPayload::class.java, OpenBarracksScreenPayload.CODEC) { ClientPayloadHandlers.openBarracksScreen(it.pos, it.config) }
        toClient(BarracksQueuePayload::class.java, BarracksQueuePayload.CODEC) { ClientPayloadHandlers.barracksQueue(it.pos, it.data) }
        toClient(OpenSupplyScreenPayload::class.java, OpenSupplyScreenPayload.CODEC) { ClientPayloadHandlers.openSupplyScreen(it.pos, it.data) }
    }
}

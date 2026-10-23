package com.sbwnpc.squad.network

import com.sbwnpc.squad.client.ClientPayloadHandlers
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext

/** [PayloadContext] over NeoForge's [IPayloadContext]. */
private class NeoForgePayloadContext(private val context: IPayloadContext) : PayloadContext {
    override fun player() = context.player()
    override fun enqueueWork(task: Runnable) {
        context.enqueueWork(task)
    }
}

@EventBusSubscriber
object ModNetwork {
    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        val r = event.registrar("2")

        r.playToServer(ConfigureToolPayload.TYPE, ConfigureToolPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onConfigureTool(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(ToggleToolModePayload.TYPE, ToggleToolModePayload.CODEC) { _, ctx -> ServerPayloadHandlers.onToggleToolMode(NeoForgePayloadContext(ctx)) }
        r.playToServer(SquadCmdPayload.TYPE, SquadCmdPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onSquadCmd(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(RequestHudPayload.TYPE, RequestHudPayload.CODEC) { _, ctx -> ServerPayloadHandlers.onRequestHud(NeoForgePayloadContext(ctx)) }
        r.playToServer(HudOrderPayload.TYPE, HudOrderPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onHudOrder(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(HudOrderAllPayload.TYPE, HudOrderAllPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onHudOrderAll(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(ChooseFactionPayload.TYPE, ChooseFactionPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onChooseFaction(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(RouteCmdPayload.TYPE, RouteCmdPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onRouteCmd(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(ConfigureBarracksPayload.TYPE, ConfigureBarracksPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onConfigureBarracks(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(RequestBarracksQueuePayload.TYPE, RequestBarracksQueuePayload.CODEC) { p, ctx -> ServerPayloadHandlers.onRequestBarracksQueue(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(DiplomacyCmdPayload.TYPE, DiplomacyCmdPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onDiplomacyCmd(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(SupplyCmdPayload.TYPE, SupplyCmdPayload.CODEC) { p, ctx -> ServerPayloadHandlers.onSupplyCmd(p, NeoForgePayloadContext(ctx)) }
        r.playToServer(MapSubscribePayload.TYPE, MapSubscribePayload.CODEC) { _, ctx -> ServerPayloadHandlers.onMapSubscribe(NeoForgePayloadContext(ctx)) }

        r.playToClient(OpenCommandScreenPayload.TYPE, OpenCommandScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openCommandScreen(p.data)
        }
        r.playToClient(SquadDetailsPayload.TYPE, SquadDetailsPayload.CODEC) { p, ctx ->
            if (FMLEnvironment.dist == Dist.CLIENT) ctx.enqueueWork { ClientPayloadHandlers.squadDetails(p.data) }
        }
        r.playToClient(OpenHudPayload.TYPE, OpenHudPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openHud(p.data)
        }
        r.playToClient(OpenFactionPickPayload.TYPE, OpenFactionPickPayload.CODEC) { _, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openFactionPick()
        }
        r.playToClient(PlayerFactionPayload.TYPE, PlayerFactionPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.playerFaction(p.faction)
        }
        r.playToClient(OpenRecruitScreenPayload.TYPE, OpenRecruitScreenPayload.CODEC) { _, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openRecruitScreenFromHeldItem()
        }
        r.playToClient(OpenRoutesScreenPayload.TYPE, OpenRoutesScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openRoutesScreen(p.data)
        }
        r.playToClient(OpenFinishRoutePayload.TYPE, OpenFinishRoutePayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openFinishRoute(p.pointCount)
        }
        r.playToClient(MapFeedPayload.TYPE, MapFeedPayload.CODEC) { p, ctx ->
            if (FMLEnvironment.dist == Dist.CLIENT) ctx.enqueueWork { ClientPayloadHandlers.mapFeed(p.data) }
        }
        r.playToClient(OpenDiplomacyPayload.TYPE, OpenDiplomacyPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openDiplomacyScreen(p.data)
        }
        r.playToClient(OpenBarracksScreenPayload.TYPE, OpenBarracksScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openBarracksScreen(p.pos, p.config)
        }
        r.playToClient(BarracksQueuePayload.TYPE, BarracksQueuePayload.CODEC) { p, ctx ->
            ctx.enqueueWork {
                if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.barracksQueue(p.pos, p.data)
            }
        }
        r.playToClient(OpenSupplyScreenPayload.TYPE, OpenSupplyScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openSupplyScreen(p.pos, p.data)
        }
    }
}

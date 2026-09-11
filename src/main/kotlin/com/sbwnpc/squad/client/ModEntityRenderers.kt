package com.sbwnpc.squad.client

import com.sbwnpc.squad.client.renderer.BarracksRenderer
import com.sbwnpc.squad.client.renderer.NpcRenderer
import com.sbwnpc.squad.init.ModEntities
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.EntityRenderersEvent

@EventBusSubscriber(Dist.CLIENT)
object ModEntityRenderers {
    @SubscribeEvent
    fun registerLayerDefinitions(event: EntityRenderersEvent.RegisterLayerDefinitions) {
        event.registerLayerDefinition(NpcModel.LAYER, NpcModel::createBodyLayer)
        event.registerLayerDefinition(BarracksModel.LAYER, BarracksModel::createBodyLayer)
    }

    @SubscribeEvent
    fun registerEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(ModEntities.NPC.get(), ::NpcRenderer)
        event.registerEntityRenderer(ModEntities.BARRACKS.get(), ::BarracksRenderer)
    }
}

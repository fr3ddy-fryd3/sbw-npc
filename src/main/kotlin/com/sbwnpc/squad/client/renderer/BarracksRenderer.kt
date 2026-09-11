package com.sbwnpc.squad.client.renderer

import com.sbwnpc.squad.client.BarracksModel
import com.sbwnpc.squad.entity.BarracksEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation

class BarracksRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<BarracksEntity, BarracksModel>(context, BarracksModel(context.bakeLayer(BarracksModel.LAYER)), 0.5f) {

    override fun getTextureLocation(entity: BarracksEntity): ResourceLocation = TEXTURE

    companion object {
        private val TEXTURE = ResourceLocation.fromNamespaceAndPath("superbwarfare", "textures/block/sandbag.png")
    }
}

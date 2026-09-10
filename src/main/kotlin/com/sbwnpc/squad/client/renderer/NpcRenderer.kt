package com.sbwnpc.squad.client.renderer

import com.sbwnpc.squad.SquadMod.Companion.loc
import com.sbwnpc.squad.client.NpcModel
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.resources.ResourceLocation

class NpcRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<NpcEntity, NpcModel>(context, NpcModel(context.bakeLayer(NpcModel.LAYER)), 0.5f) {

    override fun getTextureLocation(entity: NpcEntity): ResourceLocation = TEXTURE

    companion object {
        val TEXTURE: ResourceLocation = loc("textures/entity/npc.png")
    }
}

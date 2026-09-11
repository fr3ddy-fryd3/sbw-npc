package com.sbwnpc.squad.client.renderer

import com.sbwnpc.squad.client.NpcModel
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.resources.ResourceLocation

class NpcRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<NpcEntity, NpcModel>(context, NpcModel(context.bakeLayer(NpcModel.LAYER)), 0.5f) {

    // Faction comes purely from the entity's (client-synced) scoreboard team — no extra synced
    // data needed. Unteamed (neutral) NPCs fall back to the default faction's skin.
    override fun getTextureLocation(entity: NpcEntity): ResourceLocation =
        (SquadTeams.factionOf(entity) ?: SquadFaction.DEFAULT).texture
}

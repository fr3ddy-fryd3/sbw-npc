package com.sbwnpc.squad.client.renderer

import com.sbwnpc.squad.client.NpcModel
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.resources.ResourceLocation

class NpcRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<NpcEntity, NpcModel>(context, NpcModel(context.bakeLayer(NpcModel.LAYER)), 0.5f) {

    init {
        // The base HumanoidMobRenderer constructor (3-arg, no layers) never adds armor rendering on
        // its own — vanilla mobs that show armor (Zombie/Husk/Skeleton/...) all explicitly add this
        // layer themselves (confirmed against AbstractZombieRenderer's real source, not guessed).
        // Without it, NpcEntity.applyRole()'s setItemSlot(HEAD/CHEST, ...) equips real armor that
        // never gets drawn — reported in-game as "броня не отображается внешне". PLAYER_INNER/
        // OUTER_ARMOR (not e.g. ZOMBIE's) because NpcModel is standard player proportions (its own
        // doc comment: "Plain vanilla humanoid, standard 64x64 player-skin proportions").
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.modelManager
            )
        )

        // HumanoidMobRenderer's constructor has already added the vanilla in-hand layer; swap it
        // for the distance-limited one. See NpcItemInHandLayer for what an SBW gun costs to draw
        // and why this is the lever that matters.
        layers.removeIf { it is ItemInHandLayer<*, *> }
        addLayer(NpcItemInHandLayer(this, context.itemInHandRenderer))
    }

    // Faction comes purely from the entity's (client-synced) scoreboard team — no extra synced
    // data needed. Unteamed (neutral) NPCs fall back to the default faction's skin.
    override fun getTextureLocation(entity: NpcEntity): ResourceLocation =
        (SquadTeams.factionOf(entity) ?: SquadFaction.DEFAULT).texture
}

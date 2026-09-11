package com.sbwnpc.squad.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.entity.BarracksEntity
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition

/**
 * A plain box placeholder, textured with SBW's own sandbag block texture rather than a
 * purpose-authored asset — visual polish for a proper structure model is a separate concern from
 * the entity's actual behavior (destructible, respawns troops) and can be improved later without
 * touching any of that logic.
 */
class BarracksModel(private val root: ModelPart) : EntityModel<BarracksEntity>() {

    override fun setupAnim(
        entity: BarracksEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float
    ) {
        // Static — nothing to animate.
    }

    override fun renderToBuffer(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, color: Int) {
        root.render(poseStack, buffer, packedLight, packedOverlay, color)
    }

    companion object {
        val LAYER = ModelLayerLocation(SquadMod.loc("barracks"), "main")

        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            mesh.root.addOrReplaceChild(
                "box",
                CubeListBuilder.create().texOffs(0, 0).addBox(-12f, -32f, -12f, 24f, 32f, 24f),
                PartPose.ZERO
            )
            return LayerDefinition.create(mesh, 32, 32)
        }
    }
}

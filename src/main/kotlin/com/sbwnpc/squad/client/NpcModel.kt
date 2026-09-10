package com.sbwnpc.squad.client

import com.atsuishio.superbwarfare.client.PoseTool
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.world.InteractionHand

/**
 * Plain vanilla humanoid (standard 64x64 player-skin proportions). The only custom bit is the
 * arm pose: SuperbWarfare only wires its gun `ArmPose` (`PoseTool.pose` -> BOW_AND_ARROW /
 * CROSSBOW_CHARGE) into the *player* renderer, not arbitrary mobs, so we set it here — reusing
 * SBW's own PoseTool rather than authoring poses. Everything else (walk/idle/swing/death,
 * ItemInHandLayer, head/armor layers) comes from HumanoidModel / HumanoidMobRenderer.
 */
class NpcModel(root: ModelPart) : HumanoidModel<NpcEntity>(root) {

    override fun setupAnim(
        entity: NpcEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float
    ) {
        val stack = entity.mainHandItem
        val pose = if (stack.item is GunItem) {
            PoseTool.pose(entity, InteractionHand.MAIN_HAND, stack)
        } else {
            ArmPose.EMPTY
        }
        this.rightArmPose = pose
        this.leftArmPose = pose

        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch)
    }

    companion object {
        val LAYER = ModelLayerLocation(SquadMod.loc("npc"), "main")

        fun createBodyLayer(): LayerDefinition =
            LayerDefinition.create(createMesh(CubeDeformation.NONE, 0.0f), 64, 64)
    }
}

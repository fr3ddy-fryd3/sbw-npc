package com.sbwnpc.squad.client.renderer

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance
import com.maydaymemory.mae.basic.ArrayPoseBuilder
import com.maydaymemory.mae.basic.ZYXBoneTransformFactory
import com.maydaymemory.mae.blend.EulerAdditiveBlender
import com.maydaymemory.mae.blend.SimpleEulerAdditiveBlender
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.sbwnpc.squad.SquadMod.Companion.loc
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext

class NpcRenderer(renderManager: EntityRendererProvider.Context) : EntityRenderer<NpcEntity>(renderManager) {
    init {
        this.shadowRadius = 0.4f
    }

    override fun getTextureLocation(pEntity: NpcEntity): ResourceLocation {
        return TEXTURE
    }

    override fun render(
        pEntity: NpcEntity,
        pEntityYaw: Float,
        pPartialTick: Float,
        pPoseStack: PoseStack,
        pBuffer: MultiBufferSource,
        pPackedLight: Int
    ) {
        val ani = pEntity.animationInstance ?: return
        val instance = pEntity.modelInstance ?: return

        pPoseStack.pushPose()
        pPoseStack.mulPose(Axis.YP.rotationDegrees(180f))
        pPoseStack.mulPose(Axis.YP.rotationDegrees(-pEntity.getViewYRot(pPartialTick)))

        ani.context.partialTick = pPartialTick
        ani.tick()
        instance.applyPose(BLENDER.blend(instance.bindPose, ani.getPose()))

        instance.renderToBuffer(
            pPoseStack,
            pBuffer,
            RenderType.entityCutout(getTextureLocation(pEntity)),
            BedrockModelRenderTypes.polyMeshCutout(getTextureLocation(pEntity)),
            pPackedLight,
            OverlayTexture.pack(0f, pEntity.hurtTime > 0 || pEntity.deathTime > 0)
        )

        renderHeldItem(pEntity, instance, pPoseStack, pBuffer, pPackedLight)

        pPoseStack.popPose()
    }

    // The SBM skeleton has no built-in hand attachment point (no locator defined in the geo.json
    // yet), so this hangs the item off the right_arm bone's own transform with a hand-tuned local
    // offset. Since this is a plain EntityRenderer (not LivingEntityRenderer), nothing renders the
    // held item for us the way it would for a vanilla humanoid mob — has to be done by hand here.
    private fun renderHeldItem(
        pEntity: NpcEntity,
        instance: BakedModelInstance,
        pPoseStack: PoseStack,
        pBuffer: MultiBufferSource,
        pPackedLight: Int
    ) {
        val stack = pEntity.mainHandItem
        if (stack.isEmpty) return

        val boneIndex = instance.getIndex("right_arm")
        if (boneIndex < 0) return

        pPoseStack.pushPose()
        instance.mulGlobalTransform(pPoseStack, boneIndex)
        // right_arm's pivot is at the shoulder; the cube hangs ~12 bedrock units (0.75 block)
        // below it down to roughly where the hand is.
        pPoseStack.translate(0.0, -0.75, 0.0)
        pPoseStack.mulPose(Axis.XP.rotationDegrees(-90f))

        Minecraft.getInstance().itemRenderer.renderStatic(
            stack,
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
            pPackedLight,
            OverlayTexture.NO_OVERLAY,
            pPoseStack,
            pBuffer,
            pEntity.level(),
            pEntity.id
        )
        pPoseStack.popPose()
    }

    companion object {
        val TEXTURE: ResourceLocation = loc("textures/bedrock/entity/npc_placeholder.png")
        val BLENDER: EulerAdditiveBlender = SimpleEulerAdditiveBlender(ZYXBoneTransformFactory()) { ArrayPoseBuilder() }
    }
}

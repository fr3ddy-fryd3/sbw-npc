package com.sbwnpc.squad.client.renderer

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes
import com.maydaymemory.mae.basic.ArrayPoseBuilder
import com.maydaymemory.mae.basic.ZYXBoneTransformFactory
import com.maydaymemory.mae.blend.EulerAdditiveBlender
import com.maydaymemory.mae.blend.SimpleEulerAdditiveBlender
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.sbwnpc.squad.SquadMod
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

        renderHeldItem(pEntity, pPoseStack, pBuffer, pPackedLight)

        pPoseStack.popPose()
    }

    // The model's right_arm is bedrock-identical to vanilla HumanoidModel.rightArm (same 4x12x4
    // cube, same [-5, 22, 0] pivot) since it now uses the standard player-skin UV layout — so this
    // reuses vanilla's own ItemInHandLayer.renderArmWithItem() transform sequence verbatim
    // (net/minecraft/client/renderer/entity/layers/ItemInHandLayer.java) instead of guessing:
    // translate to the arm pivot, rotate -90 X then 180 Y, then the same small (1/16, 0.125,
    // -0.625) nudge vanilla uses to reach from shoulder to grip. Still a fixed position — doesn't
    // yet follow arm-swing animation, that needs a real bone/locator attachment.
    private fun renderHeldItem(
        pEntity: NpcEntity,
        pPoseStack: PoseStack,
        pBuffer: MultiBufferSource,
        pPackedLight: Int
    ) {
        val stack = pEntity.mainHandItem
        if (stack.isEmpty) return

        pPoseStack.pushPose()
        try {
            pPoseStack.translate(-5.0 / 16.0, 22.0 / 16.0, 0.0)
            pPoseStack.mulPose(Axis.XP.rotationDegrees(-90f))
            pPoseStack.mulPose(Axis.YP.rotationDegrees(180f))
            pPoseStack.translate(1.0 / 16.0, 0.125, -0.625)

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
        } catch (e: Exception) {
            SquadMod.LOGGER.error("[render-debug] renderHeldItem threw", e)
        } finally {
            pPoseStack.popPose()
        }
    }

    companion object {
        val TEXTURE: ResourceLocation = loc("textures/bedrock/entity/npc_placeholder.png")
        val BLENDER: EulerAdditiveBlender = SimpleEulerAdditiveBlender(ZYXBoneTransformFactory()) { ArrayPoseBuilder() }
    }
}

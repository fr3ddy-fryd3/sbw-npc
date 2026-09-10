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

    // BakedModelInstance.mulGlobalTransform() turned out not to give a usable world-space bone
    // position here (logged near-zero translation, and the item rendered at the model's feet) —
    // its semantics don't match a simple "transform to this bone's pivot" the way I assumed.
    // Sidestepping that entirely for now: this renders in the SAME raw model-space the body mesh
    // itself uses (bedrock units / 16 = blocks), translated straight to right_arm's own pivot/cube
    // numbers from npc_placeholder.geo.json (pivot [-5, 22, 0], cube runs down to y=10 — the hand).
    // Fixed position, doesn't track arm-swing animation yet — proper bone/locator attachment is a
    // later refinement.
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
            pPoseStack.translate(-5.0 / 16.0, 10.0 / 16.0, 0.0)

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

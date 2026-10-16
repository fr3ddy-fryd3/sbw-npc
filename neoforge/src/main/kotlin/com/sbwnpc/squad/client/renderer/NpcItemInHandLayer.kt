package com.sbwnpc.squad.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.sbwnpc.squad.client.NpcModel
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.PerfProbe
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemInHandRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * Draws held items within four chunks of the camera. Guns use a separate static renderer:
 * vanilla's item renderer would delegate them to SBW's animated GeckoLib renderer every frame.
 * Other held items retain their normal item rendering.
 */
class NpcItemInHandLayer(
    renderer: RenderLayerParent<NpcEntity, NpcModel>,
    itemInHandRenderer: ItemInHandRenderer
) : ItemInHandLayer<NpcEntity, NpcModel>(renderer, itemInHandRenderer) {

    override fun render(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        entity: NpcEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        partialTicks: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float
    ) {
        // Against the camera rather than the local player: in spectator, or on a detached/third
        // person camera, the player is not where the view is.
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        if (entity.distanceToSqr(camera.x, camera.y, camera.z) > RENDER_DISTANCE * RENDER_DISTANCE) return
        PerfProbe.Client.countWeaponDrawn()
        traceEmptyHand(entity)
        super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch)
    }

    override fun renderArmWithItem(
        entity: LivingEntity,
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        arm: HumanoidArm,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        if (stack.isEmpty) return
        if (!Ports.gunRendering.supports(stack)) {
            super.renderArmWithItem(entity, stack, displayContext, arm, poseStack, buffer, packedLight)
            return
        }

        poseStack.pushPose()
        try {
            parentModel.translateToHand(arm, poseStack)
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f))
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
            poseStack.translate(if (arm == HumanoidArm.LEFT) -1.0f / 16 else 1.0f / 16, 0.125f, -0.625f)
            Ports.gunRendering.render(entity, stack, displayContext, poseStack, buffer, packedLight)
        } finally {
            poseStack.popPose()
        }
    }

    /** "Spawned with no weapon": says whether this client has an item for the hand at all. Empty
     *  here means the equipment never arrived; not listed while the NPC shows no gun means it did
     *  and the model is not being drawn. Once per NPC. */
    private fun traceEmptyHand(entity: NpcEntity) {
        if (!com.sbwnpc.squad.combat.DebugFlags.on(com.sbwnpc.squad.combat.LogGroup.EQUIP) || !entity.mainHandItem.isEmpty) return
        if (entity.tickCount < EMPTY_HAND_GRACE_TICKS || !reportedEmpty.add(entity.id)) return
        com.sbwnpc.squad.SquadMod.LOGGER.info(
            "[equip-debug] client: NPC {} (entity {}) has an empty main hand {} ticks after appearing; offhand={} head={} chest={} vehicle={}",
            entity.uuid, entity.id, entity.tickCount, entity.offhandItem, entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD),
            entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST), entity.vehicle?.type?.descriptionId
        )
    }

    companion object {
        private const val EMPTY_HAND_GRACE_TICKS = 40
        private val reportedEmpty = HashSet<Int>()

        /** Blocks from the camera. Tuning knob for the whole trade: lower is faster, and the point
         *  at which weapons start popping in is exactly this number. */
        const val RENDER_DISTANCE = 64.0
    }
}

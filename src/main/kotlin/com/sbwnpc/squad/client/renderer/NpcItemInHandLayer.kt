package com.sbwnpc.squad.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.sbwnpc.squad.client.NpcModel
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.PerfProbe
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemInHandRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer

/**
 * Draws an NPC's weapon only while it is close enough to see, and nothing at all past that.
 *
 * SuperbWarfare's guns are GeckoLib items: every one in a hand is a full animated model, built and
 * submitted to a translucent pass once per frame per holder. Measured on a real fight, our NPCs
 * cost ~0.36 ms each per frame to draw against a ~0.02-0.05 ms vanilla mob, and the frame rate
 * tracked the number of NPCs *in frame* rather than the number loaded — 148 loaded with 20 on
 * screen ran at 34 fps, the same 148 with 147 on screen at 11, on an unchanged server tick.
 *
 * GeckolibBetterFPS takes roughly 40% of that off by throttling the animation, which is worth
 * having, but it cannot help with the geometry: the model is still assembled and drawn. Distance
 * is the only lever that removes the work entirely, and it costs almost nothing visually — past
 * [RENDER_DISTANCE] a rifle is a few pixels against the body holding it.
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
        super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch)
    }

    companion object {
        /** Blocks from the camera. Tuning knob for the whole trade: lower is faster, and the point
         *  at which weapons start popping in is exactly this number. */
        const val RENDER_DISTANCE = 32.0
    }
}

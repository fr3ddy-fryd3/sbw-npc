package com.sbwnpc.squad.domain.port

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/** Client only: a gun's static third-person model, after the layer has attached it to the hand. */
interface GunRendering {
    fun supports(stack: ItemStack): Boolean

    fun render(
        holder: LivingEntity,
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    )
}

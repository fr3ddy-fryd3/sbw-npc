package com.sbwnpc.squad.integration.sbw.client

import com.sbwnpc.squad.domain.port.ClientPorts
import com.sbwnpc.squad.domain.port.GunRendering
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * The client ports on Forge: NPCs hold an SBW gun in the arm pose SBW gives it ([SbwGunPoses]) and
 * draw it with the ordinary item renderer. Not yet ported: the NeoForge module draws SBW's static
 * gun model.
 */
object SbwClientPorts {
    fun install() {
        ClientPorts.gunPoses = SbwGunPoses
        ClientPorts.gunRendering = object : GunRendering {
            override fun supports(stack: ItemStack) = false

            override fun render(
                holder: LivingEntity,
                stack: ItemStack,
                displayContext: ItemDisplayContext,
                poseStack: PoseStack,
                buffer: MultiBufferSource,
                packedLight: Int,
            ) = Unit
        }
    }
}

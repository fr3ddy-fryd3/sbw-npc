package com.sbwnpc.squad.integration.sbw.client

import com.sbwnpc.squad.domain.port.ClientPorts
import com.sbwnpc.squad.domain.port.GunPoses
import com.sbwnpc.squad.domain.port.GunRendering
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * The client ports on Fabric: NPCs hold an SBW gun in the default arm pose and draw it with the
 * ordinary item renderer. Not yet ported: the NeoForge module reads SBW's own arm pose and draws its static
 * gun model; port both to ReFabricated.
 */
object SbwClientPorts {
    fun install() {
        ClientPorts.gunPoses = object : GunPoses {
            override fun armPose(holder: LivingEntity, stack: ItemStack): HumanoidModel.ArmPose? = null
        }
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

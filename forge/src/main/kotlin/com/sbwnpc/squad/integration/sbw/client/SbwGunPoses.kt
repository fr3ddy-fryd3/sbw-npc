package com.sbwnpc.squad.integration.sbw.client

import com.atsuishio.superbwarfare.client.PoseTool
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.domain.port.GunPoses
import net.minecraft.client.model.HumanoidModel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

/**
 * SBW only wires its gun `ArmPose` into the player renderer, not arbitrary mobs; this lends the
 * same poses to ours.
 *
 * SBW for 1.20.1 has no client item extension that answers with a pose, so [PoseTool.pose] is the
 * only source here. The NeoForge adapter asks the extension first because SBW 1.21 gives the M2HB
 * and the minigun a hold of their own there.
 */
object SbwGunPoses : GunPoses {
    override fun armPose(holder: LivingEntity, stack: ItemStack): HumanoidModel.ArmPose? =
        if (stack.item is GunItem) PoseTool.pose(holder, InteractionHand.MAIN_HAND, stack) else null
}

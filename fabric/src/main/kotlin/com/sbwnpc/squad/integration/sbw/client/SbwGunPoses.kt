package com.sbwnpc.squad.integration.sbw.client

import com.atsuishio.superbwarfare.client.PoseTool
import com.atsuishio.superbwarfare.item.gun.GunGeoItem
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
 * Asked of the gun item itself, as the player renderer does, not [PoseTool.pose] directly: most
 * guns answer with that rifle pose, but the M2HB and the minigun have their own two-handed low hold.
 */
object SbwGunPoses : GunPoses {
    override fun armPose(holder: LivingEntity, stack: ItemStack): HumanoidModel.ArmPose? {
        val item = stack.item as? GunItem ?: return null
        return (item as? GunGeoItem)?.getArmPose(holder, InteractionHand.MAIN_HAND, stack)
            ?: PoseTool.pose(holder, InteractionHand.MAIN_HAND, stack)
    }
}

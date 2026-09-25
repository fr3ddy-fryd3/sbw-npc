package com.sbwnpc.squad.integration.sbw.client

import com.atsuishio.superbwarfare.client.PoseTool
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.domain.port.GunPoses
import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.client.model.HumanoidModel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent

/** SBW only wires its gun `ArmPose` (`PoseTool.pose` -> BOW_AND_ARROW / CROSSBOW_CHARGE) into the
 *  player renderer, not arbitrary mobs; this lends the same poses to ours. */
@EventBusSubscriber(Dist.CLIENT)
object SbwGunPoses : GunPoses {
    override fun armPose(holder: LivingEntity, stack: ItemStack): HumanoidModel.ArmPose? =
        if (stack.item is GunItem) PoseTool.pose(holder, InteractionHand.MAIN_HAND, stack) else null

    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        Ports.gunPoses = SbwGunPoses
    }
}

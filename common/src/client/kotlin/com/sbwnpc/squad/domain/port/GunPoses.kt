package com.sbwnpc.squad.domain.port

import net.minecraft.client.model.HumanoidModel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

/** Client only: how a mob holds a gun in its arms. Wired on client setup, never on a server. */
interface GunPoses {
    /** Null when [stack] isn't a gun. */
    fun armPose(holder: LivingEntity, stack: ItemStack): HumanoidModel.ArmPose?
}

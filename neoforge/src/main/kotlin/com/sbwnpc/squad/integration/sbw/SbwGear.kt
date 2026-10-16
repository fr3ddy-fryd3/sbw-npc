package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModTags
import com.atsuishio.superbwarfare.item.misc.MedicalKitItem
import com.sbwnpc.squad.domain.port.Gear
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

object SbwGear : Gear {
    override fun uniform(green: Boolean): Pair<ItemStack, ItemStack> =
        if (green) ItemStack(ModItems.RU_HELMET_6B47.get()) to ItemStack(ModItems.RU_CHEST_6B43.get())
        else ItemStack(ModItems.US_HELMET_PASGT.get()) to ItemStack(ModItems.US_CHEST_IOTV.get())

    override fun treat(patient: LivingEntity) {
        (ModItems.MEDICAL_KIT.get() as MedicalKitItem).treat(patient)
    }

    // NOT vanilla's DamageTypeTags.IS_PROJECTILE — SBW's gunfire damage types (GUN_FIRE,
    // GUN_FIRE_HEADSHOT, the ones actually dealt by every rifle/MG/sniper hit) are never members of
    // that vanilla tag. SBW tags them under its OWN ModTags.DamageTypes.PROJECTILE instead.
    override fun isBulletDamage(source: DamageSource): Boolean = source.`is`(ModTags.DamageTypes.PROJECTILE)
}

package com.sbwnpc.squad.domain.port

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

/** Everything an NPC wears or carries that isn't a weapon. */
interface Gear {
    /** Helmet and body armour: [green] for the RU pattern, otherwise the US one. */
    fun uniform(green: Boolean): Pair<ItemStack, ItemStack>

    /** What a medic does with one use of a medical kit. */
    fun treat(patient: LivingEntity)

    /** Gunfire of any kind — what makes an NPC feel suppressed. */
    fun isBulletDamage(source: DamageSource): Boolean
}

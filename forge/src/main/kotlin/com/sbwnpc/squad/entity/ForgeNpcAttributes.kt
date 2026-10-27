package com.sbwnpc.squad.entity

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraftforge.common.ForgeMod

/** Adds Forge's swim attribute and updates old saved values to the current pace. */
object ForgeNpcAttributes {
    // Twice the usual pace in water so a squad can ford a river without crawling.
    private const val SWIM_SPEED = 2.0

    fun create(): AttributeSupplier.Builder = NpcEntity.createAttributes().add(ForgeMod.SWIM_SPEED.get(), SWIM_SPEED)

    fun restoreSavedSwimSpeed(npc: LivingEntity) {
        npc.getAttribute(ForgeMod.SWIM_SPEED.get())?.baseValue = SWIM_SPEED
    }
}

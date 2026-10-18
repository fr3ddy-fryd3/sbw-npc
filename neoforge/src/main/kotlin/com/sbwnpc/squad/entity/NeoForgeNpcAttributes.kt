package com.sbwnpc.squad.entity

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.neoforged.neoforge.common.NeoForgeMod

/** Adds NeoForge's swim attribute and updates old saved values to the current pace. */
object NeoForgeNpcAttributes {
    // Twice the usual pace in water so a squad can ford a river without crawling.
    private const val SWIM_SPEED = 2.0

    fun create(): AttributeSupplier.Builder = NpcEntity.createAttributes().add(NeoForgeMod.SWIM_SPEED, SWIM_SPEED)

    fun restoreSavedSwimSpeed(npc: LivingEntity) {
        npc.getAttribute(NeoForgeMod.SWIM_SPEED)?.baseValue = SWIM_SPEED
    }
}

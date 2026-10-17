package com.sbwnpc.squad.npc

import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes

/** Vanilla attributes shared by NPC implementations on every loader. */
object NpcAttributes {
    const val BASE_HEALTH = 20.0
    const val BASE_SPEED = 0.25

    fun create(): AttributeSupplier.Builder =
        Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, BASE_HEALTH)
            .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
            .add(Attributes.ATTACK_DAMAGE, 2.0)
            .add(Attributes.ARMOR, 2.0)
            // Detection uses a separate 72-block limit. FOLLOW_RANGE also sizes the pathfinder's
            // search at construction (maxVisitedNodes = FOLLOW_RANGE * 16), so keeping it at 48
            // avoids expanding every repath to the larger detection radius.
            .add(Attributes.FOLLOW_RANGE, 48.0)
}

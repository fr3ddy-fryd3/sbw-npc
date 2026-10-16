package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcRegistry
import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.level.ExplosionEvent

/** Suppresses every NPC near an explosion (mortar shell, grenade), hit or not — a close call
 *  should send people diving for cover even without taking direct damage. Direct-hit suppression
 *  (ranged damage) is handled in [NpcEntity.hurt] instead, which already has the right context. */
@EventBusSubscriber
object SuppressionEvents {

    private const val RADIUS = 6.0

    @SubscribeEvent
    fun onExplosion(event: ExplosionEvent.Detonate) {
        val level = event.level as? ServerLevel ?: return
        val pos = event.explosion.center()
        NpcRegistry.forEachWithin(level, pos, RADIUS) { it.suppress(pos) }
    }
}

package com.sbwnpc.squad.entity

import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import java.util.IdentityHashMap

/**
 * Live index of every loaded piloted drone (SBW's own and the Drone Warfare addon's alike) per level — the [NpcRegistry] idea for a class we don't own, so it's fed from
 * NeoForge's join/leave events instead of entity overrides. `AntiDroneBehaviour` asks "any hostile
 * drone near me?" from every NPC every few ticks; walking this (a handful of entries) beats a
 * 96-block entity box query per NPC.
 */
@EventBusSubscriber
object DroneRegistry {
    private val byLevel = IdentityHashMap<ServerLevel, LinkedHashSet<Entity>>()

    @SubscribeEvent
    fun onJoin(event: EntityJoinLevelEvent) {
        val drone = event.entity.takeIf(Ports.drones::isPiloted) ?: return
        val level = event.level as? ServerLevel ?: return
        byLevel.getOrPut(level) { LinkedHashSet() }.add(drone)
    }

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) {
        val drone = event.entity.takeIf(Ports.drones::isPiloted) ?: return
        val level = event.level as? ServerLevel ?: return
        val set = byLevel[level] ?: return
        set.remove(drone)
        if (set.isEmpty()) byLevel.remove(level)
    }

    fun all(level: ServerLevel): Collection<Entity> = byLevel[level] ?: emptyList()

    fun clearAll() = byLevel.clear()
}

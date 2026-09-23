package com.sbwnpc.squad.entity

import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity
import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import java.util.IdentityHashMap

/**
 * Every live hand grenade per level — the [DroneRegistry] idea again. Every NPC asks "is a
 * grenade about to go off next to me?" every tick, and the answer is almost always "there are no
 * grenades at all", which this makes a single empty-map lookup.
 *
 * Only `HandGrenadeEntity` (the M67, and anything built on it): it's the one on a timed fuse that
 * lies there long enough to run from. The RGO and launcher rounds go off on contact.
 */
@EventBusSubscriber
object GrenadeRegistry {
    private val byLevel = IdentityHashMap<ServerLevel, LinkedHashSet<HandGrenadeEntity>>()

    @SubscribeEvent
    fun onJoin(event: EntityJoinLevelEvent) {
        val grenade = event.entity as? HandGrenadeEntity ?: return
        val level = event.level as? ServerLevel ?: return
        byLevel.getOrPut(level) { LinkedHashSet() }.add(grenade)
    }

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) {
        val grenade = event.entity as? HandGrenadeEntity ?: return
        val level = event.level as? ServerLevel ?: return
        val set = byLevel[level] ?: return
        set.remove(grenade)
        if (set.isEmpty()) byLevel.remove(level)
    }

    fun all(level: ServerLevel): Collection<HandGrenadeEntity> = byLevel[level] ?: emptyList()

    fun clearAll() = byLevel.clear()
}

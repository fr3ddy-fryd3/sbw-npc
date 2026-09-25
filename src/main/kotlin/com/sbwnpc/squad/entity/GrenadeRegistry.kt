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
 * Every live hand grenade per level — the [DroneRegistry] idea again. Every NPC asks "is a
 * grenade about to go off next to me?" every tick, and the answer is almost always "there are no
 * grenades at all", which this makes a single empty-map lookup.
 *
 * Only grenades on a timed fuse: they lie there long enough to run from. Contact-fuzed rounds go
 * off before anyone could react.
 */
@EventBusSubscriber
object GrenadeRegistry {
    private val byLevel = IdentityHashMap<ServerLevel, LinkedHashSet<Entity>>()

    @SubscribeEvent
    fun onJoin(event: EntityJoinLevelEvent) {
        val grenade = event.entity.takeIf(Ports.grenades::isTimedGrenade) ?: return
        val level = event.level as? ServerLevel ?: return
        byLevel.getOrPut(level) { LinkedHashSet() }.add(grenade)
    }

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) {
        val grenade = event.entity.takeIf(Ports.grenades::isTimedGrenade) ?: return
        val level = event.level as? ServerLevel ?: return
        val set = byLevel[level] ?: return
        set.remove(grenade)
        if (set.isEmpty()) byLevel.remove(level)
    }

    fun all(level: ServerLevel): Collection<Entity> = byLevel[level] ?: emptyList()

    fun clearAll() = byLevel.clear()
}

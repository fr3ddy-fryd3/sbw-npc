package com.sbwnpc.squad.entity

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import java.util.IdentityHashMap

/**
 * Live per-level index of the loaded entities [accepts] picks out — the [NpcRegistry] idea for
 * classes we don't own, so it is fed from NeoForge's join/leave events rather than entity
 * overrides. For kinds there are only ever a handful of: walking the index beats a box query per
 * asker, and "none at all" is a single empty-map lookup.
 *
 * Each subclass is an event subscriber that passes the two events on to [join] and [leave].
 */
open class EntityIndex(private val accepts: (Entity) -> Boolean) {
    private val byLevel = IdentityHashMap<ServerLevel, LinkedHashSet<Entity>>()

    protected fun join(event: EntityJoinLevelEvent) {
        val entity = event.entity.takeIf(accepts) ?: return
        val level = event.level as? ServerLevel ?: return
        byLevel.getOrPut(level) { LinkedHashSet() }.add(entity)
    }

    protected fun leave(event: EntityLeaveLevelEvent) {
        val entity = event.entity.takeIf(accepts) ?: return
        val level = event.level as? ServerLevel ?: return
        val set = byLevel[level] ?: return
        set.remove(entity)
        if (set.isEmpty()) byLevel.remove(level)
    }

    fun all(level: ServerLevel): Collection<Entity> = byLevel[level] ?: emptyList()

    fun clearAll() = byLevel.clear()
}

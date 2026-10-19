package com.sbwnpc.squad.entity

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import java.util.IdentityHashMap

/**
 * Live per-level index of the loaded entities [accepts] picks out — the [NpcRegistry] idea for
 * classes we don't own, so it is fed from the loader's join/leave events rather than entity
 * overrides. For kinds there are only ever a handful of: walking the index beats a box query per
 * asker, and "none at all" is a single empty-map lookup.
 *
 * The loader's join and leave events are passed on to [join] and [leave] (`EntityIndexEvents` on
 * NeoForge).
 */
open class EntityIndex(private val accepts: (Entity) -> Boolean) {
    private val byLevel = IdentityHashMap<ServerLevel, LinkedHashSet<Entity>>()

    fun join(entity: Entity, level: Level) {
        if (!accepts(entity)) return
        val server = level as? ServerLevel ?: return
        byLevel.getOrPut(server) { LinkedHashSet() }.add(entity)
    }

    fun leave(entity: Entity, level: Level) {
        if (!accepts(entity)) return
        val server = level as? ServerLevel ?: return
        val set = byLevel[server] ?: return
        set.remove(entity)
        if (set.isEmpty()) byLevel.remove(server)
    }

    fun all(level: ServerLevel): Collection<Entity> = byLevel[level] ?: emptyList()

    fun clearAll() = byLevel.clear()
}

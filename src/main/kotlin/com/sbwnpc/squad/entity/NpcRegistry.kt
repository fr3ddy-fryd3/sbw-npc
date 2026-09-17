package com.sbwnpc.squad.entity

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import java.util.IdentityHashMap

/**
 * Live server-side index of every loaded [NpcEntity] per level, maintained from
 * [NpcEntity.onAddedToLevel]/[NpcEntity.onRemovedFromLevel] (NeoForge's own entity lifecycle
 * hooks — fired for spawn/load and for death/despawn/chunk-unload alike, so the set never holds a
 * stale reference).
 *
 * Replaces the `level.getEntitiesOfClass(NpcEntity::class.java, AABB)` scans that the combat
 * helpers used to make (`Alarm`, `SuppressionEvents`, `MedicHealBehaviour`, cover/mortar
 * friendly checks, target sensing): a vanilla AABB query walks every chunk section the box
 * touches and filters every entity in them by class — for a 60-block alarm radius that's dozens of
 * sections and all the animals in them, per call, and some callers made that call every tick. A
 * linear pass over a hundred-odd NPCs with a `distanceToSqr` check is both cheaper and — more
 * importantly — independent of the AABB size, so widening a radius no longer has a hidden cost.
 *
 * Iteration order is insertion order (a `LinkedHashSet` under the identity map), so results are
 * deterministic tick to tick for the same population.
 */
object NpcRegistry {
    private val byLevel = IdentityHashMap<ServerLevel, LinkedHashSet<NpcEntity>>()

    internal fun add(npc: NpcEntity) {
        val level = npc.level() as? ServerLevel ?: return
        byLevel.getOrPut(level) { LinkedHashSet() }.add(npc)
    }

    internal fun remove(npc: NpcEntity) {
        val level = npc.level() as? ServerLevel ?: return
        val set = byLevel[level] ?: return
        set.remove(npc)
        if (set.isEmpty()) byLevel.remove(level)
    }

    /** Server shutdown doesn't run the per-entity removal callbacks — drop everything so a
     *  reloaded world starts empty (ServerLifecycle). */
    fun clearAll() = byLevel.clear()

    /** Every loaded NPC in [level] (dead-but-not-yet-removed ones included — filter on `isAlive`
     *  where it matters, same as the AABB queries this replaces). */
    fun all(level: ServerLevel): Collection<NpcEntity> = byLevel[level] ?: emptyList()

    /** NPCs within [radius] (Euclidean) of [center], excluding [exclude] — the common shape of every
     *  former `getEntitiesOfClass(NpcEntity, AABB.ofSize(pos, r*2, r*2, r*2))` call, but with a real
     *  sphere test rather than the cube those boxes actually were (a slightly tighter match for
     *  "within earshot"/"within blast radius" than before, never looser). */
    inline fun forEachWithin(
        level: ServerLevel, center: net.minecraft.world.phys.Vec3, radius: Double, exclude: Entity? = null,
        action: (NpcEntity) -> Unit
    ) {
        val r2 = radius * radius
        for (npc in all(level)) {
            if (npc === exclude) continue
            if (npc.position().distanceToSqr(center) <= r2) action(npc)
        }
    }

    /** NPCs whose bounding box intersects [box] — for the callers that genuinely need a box (the
     *  firing-cone check spans shooter→target). */
    inline fun forEachIn(level: ServerLevel, box: AABB, exclude: Entity? = null, action: (NpcEntity) -> Unit) {
        for (npc in all(level)) {
            if (npc === exclude) continue
            if (box.intersects(npc.boundingBox)) action(npc)
        }
    }
}

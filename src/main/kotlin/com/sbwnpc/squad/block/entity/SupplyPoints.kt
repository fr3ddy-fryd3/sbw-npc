package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Every Supply block currently loaded, per dimension — kept by the block entities themselves as
 * they load and unload, so finding the nearest one is a walk over a handful of entries rather than
 * a scan of the blocks around an NPC. Runtime only: a block in an unloaded chunk isn't somewhere an
 * NPC can walk to this minute anyway.
 */
object SupplyPoints {
    private val byLevel = HashMap<ResourceKey<Level>, MutableSet<SupplyBlockEntity>>()

    fun add(level: ResourceKey<Level>, be: SupplyBlockEntity) {
        byLevel.getOrPut(level) { HashSet() } += be
    }

    fun remove(level: ResourceKey<Level>, be: SupplyBlockEntity) {
        byLevel[level]?.remove(be)
    }

    /** The nearest Supply within [range] of [from] that serves [faction], or null. */
    fun nearestServing(level: ResourceKey<Level>, from: Vec3, faction: SquadFaction?, range: Double): SupplyBlockEntity? {
        var best: SupplyBlockEntity? = null
        var bestD2 = range * range
        for (be in byLevel[level] ?: return null) {
            if (be.isRemoved || !be.serves(faction)) continue
            val d2 = be.blockPos.center.distanceToSqr(from)
            if (d2 <= bestD2) {
                best = be
                bestD2 = d2
            }
        }
        return best
    }

    fun clearAll() = byLevel.clear()
}

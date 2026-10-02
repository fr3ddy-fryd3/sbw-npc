package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.Diplomacy
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3

/**
 * Known Supply positions and their factions, saved per dimension. Unloading a chunk does not
 * forget its supplies: an empty NPC can march to one from far away, loading the ground as it goes.
 * Existing worlds discover their supplies when the block entities next load; no terrain scan or
 * distant chunk load is needed to find one. Breaking a block removes its entry.
 */
class SupplyPoints : SavedData() {
    private val points = LinkedHashMap<BlockPos, SquadFaction?>()

    fun remember(pos: BlockPos, faction: SquadFaction?) {
        if (points.containsKey(pos) && points[pos] == faction) return
        points[pos.immutable()] = faction
        setDirty()
    }

    fun remove(pos: BlockPos) {
        if (!points.containsKey(pos)) return
        points.remove(pos)
        setDirty()
    }

    /** The nearest Supply within [range] of [from] that serves [faction], or null. */
    fun nearestServing(from: Vec3, faction: SquadFaction?, range: Double): BlockPos? {
        var best: BlockPos? = null
        var bestD2 = range * range
        for ((pos, own) in points) {
            if (!serves(own, faction)) continue
            val d2 = pos.center.distanceToSqr(from)
            if (d2 <= bestD2) {
                best = pos
                bestD2 = d2
            }
        }
        return best
    }

    /** Check the chosen destination when its chunk is already loaded, without waking distant land. */
    fun nearestServing(level: ServerLevel, from: Vec3, faction: SquadFaction?, range: Double): BlockPos? {
        while (true) {
            val pos = nearestServing(from, faction, range) ?: return null
            val chunk = level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) ?: return pos
            val be = chunk.getBlockEntity(pos) as? SupplyBlockEntity
            if (be == null || be.isRemoved) {
                remove(pos)
                continue
            }
            remember(pos, be.faction)
            if (be.serves(faction)) return pos
        }
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        for ((pos, faction) in points) {
            list.add(CompoundTag().apply {
                put("Pos", NbtUtils.writeBlockPos(pos))
                faction?.let { putString("Faction", it.name) }
            })
        }
        tag.put("Points", list)
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_supply_points"
        private val FACTORY = Factory({ SupplyPoints() }, { tag, _ -> load(tag) }, null)

        fun get(level: ServerLevel): SupplyPoints = level.dataStorage.computeIfAbsent(FACTORY, FILE)

        fun serves(own: SquadFaction?, other: SquadFaction?): Boolean =
            own == null || (other != null && Diplomacy.allied(own, other))

        internal fun load(tag: CompoundTag): SupplyPoints {
            val data = SupplyPoints()
            for (entry in tag.getList("Points", Tag.TAG_COMPOUND.toInt())) {
                val point = entry as CompoundTag
                val pos = NbtUtils.readBlockPos(point, "Pos").orElse(null) ?: continue
                val faction = if (point.contains("Faction")) {
                    // A malformed faction must not turn a restricted point into a public one.
                    runCatching { SquadFaction.valueOf(point.getString("Faction")) }.getOrNull() ?: continue
                } else null
                data.points[pos] = faction
            }
            return data
        }
    }
}

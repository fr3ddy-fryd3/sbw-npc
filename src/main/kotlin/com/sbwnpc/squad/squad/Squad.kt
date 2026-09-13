package com.sbwnpc.squad.squad

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import java.util.UUID

class Squad(
    val id: UUID,
    var name: String,
    var faction: SquadFaction,
    var order: SquadOrder,
    val members: MutableList<UUID>,
    var objective: BlockPos?,
    /** Entity the squad is focused on: attack it (ATTACK) or guard it (DEFEND). */
    var focusEntity: UUID?,
    val owner: UUID,
    /** Position of the Barracks block this squad resupplies from, if any — see BarracksBlockEntity.
     *  A block's position IS its stable identity (unlike an entity, no UUID needed). */
    var barracksPos: BlockPos? = null,
    /** Classes the squad was formed/last topped up with, in order — a barracks compares this
     *  against current `members.size` to know what's missing and what class to spawn next. */
    var originalComposition: List<NpcClass> = emptyList(),
    /** Patrol route this squad walks when on PATROL order, if any — see `Route`/`SquadOrderBehaviour`. */
    var routeId: UUID? = null
) {
    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.putString("Name", name)
        tag.putString("Faction", faction.name)
        tag.putInt("Order", order.ordinal)
        val list = ListTag()
        members.forEach { list.add(NbtUtils.createUUID(it)) }
        tag.put("Members", list)
        objective?.let { tag.put("Objective", NbtUtils.writeBlockPos(it)) }
        focusEntity?.let { tag.putUUID("Focus", it) }
        tag.putUUID("Owner", owner)
        barracksPos?.let { tag.put("BarracksPos", NbtUtils.writeBlockPos(it)) }
        val comp = ListTag()
        originalComposition.forEach { comp.add(IntTag.valueOf(it.ordinal)) }
        tag.put("OriginalComposition", comp)
        routeId?.let { tag.putUUID("RouteId", it) }
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): Squad {
            val members = mutableListOf<UUID>()
            tag.getList("Members", Tag.TAG_INT_ARRAY.toInt()).forEach { members.add(NbtUtils.loadUUID(it)) }
            val faction = runCatching { SquadFaction.valueOf(tag.getString("Faction")) }.getOrNull() ?: SquadFaction.DEFAULT
            val originalComposition = tag.getList("OriginalComposition", Tag.TAG_INT.toInt())
                .map { NpcClass.byOrdinal((it as IntTag).asInt) }
            return Squad(
                id = tag.getUUID("Id"),
                name = tag.getString("Name"),
                faction = faction,
                order = SquadOrder.byOrdinal(tag.getInt("Order")),
                members = members,
                objective = if (tag.contains("Objective")) NbtUtils.readBlockPos(tag, "Objective").orElse(null) else null,
                focusEntity = if (tag.hasUUID("Focus")) tag.getUUID("Focus") else null,
                owner = tag.getUUID("Owner"),
                barracksPos = if (tag.contains("BarracksPos")) NbtUtils.readBlockPos(tag, "BarracksPos").orElse(null) else null,
                originalComposition = originalComposition,
                routeId = if (tag.hasUUID("RouteId")) tag.getUUID("RouteId") else null
            )
        }
    }
}

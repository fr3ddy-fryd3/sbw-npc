package com.sbwnpc.squad.squad

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.UUID

/** A barracks needs both its position and dimension: the same coordinates exist in every world. */
data class BarracksRef(val dimension: ResourceKey<Level>, val pos: BlockPos)

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
    /** Barracks this squad resupplies from, if any. */
    var barracks: BarracksRef? = null,
    /** Classes the squad was formed/last topped up with. */
    var originalComposition: List<NpcClass> = emptyList(),
    /** Rank preserved for barracks reinforcements. */
    var rank: NpcRank = NpcRank.DEFAULT,
    /** Patrol route this squad walks when on PATROL order, if any — see `Route`/`SquadOrderBehaviour`. */
    var routeId: UUID? = null,
    /** The fixed rally point for the current MOVE command. */
    var moveAssembly: BlockPos? = null,
    /** True once every member has reached its slot at [moveAssembly]. */
    var moveFormationReady: Boolean = false
) {
    /** Bumped on every new order or objective, so members can tell a fresh command from the one
     *  they're already carrying out. Not saved: a reload is a fresh start anyway. */
    var orderStamp: Int = 0

    /** Game time the current MOVE rally started — see `SquadOrderBehaviour.tickMove`. Not saved. */
    var moveRallySince: Long = 0L

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.putString("Name", name)
        tag.putString("Faction", faction.name)
        tag.putString("Order", order.name)
        tag.putString("Rank", rank.name)
        val list = ListTag()
        members.forEach { list.add(NbtUtils.createUUID(it)) }
        tag.put("Members", list)
        objective?.let { tag.put("Objective", NbtUtils.writeBlockPos(it)) }
        focusEntity?.let { tag.putUUID("Focus", it) }
        tag.putUUID("Owner", owner)
        barracks?.let {
            tag.put("BarracksPos", NbtUtils.writeBlockPos(it.pos))
            tag.putString("BarracksDim", it.dimension.location().toString())
        }
        val comp = ListTag()
        originalComposition.forEach { comp.add(StringTag.valueOf(it.name)) }
        tag.put("OriginalComposition", comp)
        routeId?.let { tag.putUUID("RouteId", it) }
        moveAssembly?.let { tag.put("MoveAssembly", NbtUtils.writeBlockPos(it)) }
        tag.putBoolean("MoveFormationReady", moveFormationReady)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): Squad {
            val members = mutableListOf<UUID>()
            tag.getList("Members", Tag.TAG_INT_ARRAY.toInt()).forEach { members.add(NbtUtils.loadUUID(it)) }
            val faction = runCatching { SquadFaction.valueOf(tag.getString("Faction")) }.getOrNull() ?: SquadFaction.DEFAULT
            val barracks = if (tag.contains("BarracksPos")) {
                NbtUtils.readBlockPos(tag, "BarracksPos").orElse(null)?.let { pos ->
                    val dimension = if (tag.contains("BarracksDim", Tag.TAG_STRING.toInt())) {
                        ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("BarracksDim")))
                    } else Level.OVERWORLD
                    BarracksRef(dimension, pos)
                }
            } else null
            return Squad(
                id = tag.getUUID("Id"),
                name = tag.getString("Name"),
                faction = faction,
                order = loadOrder(tag),
                members = members,
                objective = if (tag.contains("Objective")) NbtUtils.readBlockPos(tag, "Objective").orElse(null) else null,
                focusEntity = if (tag.hasUUID("Focus")) tag.getUUID("Focus") else null,
                owner = tag.getUUID("Owner"),
                barracks = barracks,
                originalComposition = loadComposition(tag),
                rank = runCatching { NpcRank.valueOf(tag.getString("Rank")) }.getOrDefault(NpcRank.DEFAULT),
                routeId = if (tag.hasUUID("RouteId")) tag.getUUID("RouteId") else null,
                moveAssembly = if (tag.contains("MoveAssembly")) NbtUtils.readBlockPos(tag, "MoveAssembly").orElse(null) else null,
                moveFormationReady = tag.getBoolean("MoveFormationReady")
            )
        }

        private fun loadOrder(tag: CompoundTag): SquadOrder = when {
            tag.contains("Order", Tag.TAG_STRING.toInt()) ->
                runCatching { SquadOrder.valueOf(tag.getString("Order")) }.getOrDefault(SquadOrder.MOVE)
            tag.contains("Order", Tag.TAG_INT.toInt()) -> SquadOrder.byOrdinal(tag.getInt("Order"))
            else -> SquadOrder.MOVE
        }

        private fun loadComposition(tag: CompoundTag): List<NpcClass> {
            val names = tag.getList("OriginalComposition", Tag.TAG_STRING.toInt())
            if (names.isNotEmpty()) return names.mapNotNull { runCatching { NpcClass.valueOf(it.asString) }.getOrNull() }
            return tag.getList("OriginalComposition", Tag.TAG_INT.toInt()).map { NpcClass.byOrdinal((it as IntTag).asInt) }
        }
    }
}

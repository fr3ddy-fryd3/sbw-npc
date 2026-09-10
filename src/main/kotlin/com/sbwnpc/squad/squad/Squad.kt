package com.sbwnpc.squad.squad

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import java.util.UUID

class Squad(
    val id: UUID,
    var name: String,
    var color: ChatFormatting,
    var order: SquadOrder,
    val members: MutableList<UUID>,
    var objective: BlockPos?,
    /** Entity the squad is focused on: attack it (ATTACK) or guard it (DEFEND). */
    var focusEntity: UUID?,
    val owner: UUID
) {
    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.putString("Name", name)
        tag.putString("Color", color.getName())
        tag.putInt("Order", order.ordinal)
        val list = ListTag()
        members.forEach { list.add(NbtUtils.createUUID(it)) }
        tag.put("Members", list)
        objective?.let { tag.put("Objective", NbtUtils.writeBlockPos(it)) }
        focusEntity?.let { tag.putUUID("Focus", it) }
        tag.putUUID("Owner", owner)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): Squad {
            val members = mutableListOf<UUID>()
            tag.getList("Members", Tag.TAG_INT_ARRAY.toInt()).forEach { members.add(NbtUtils.loadUUID(it)) }
            val color = ChatFormatting.getByName(tag.getString("Color"))?.takeIf { it.isColor } ?: ChatFormatting.WHITE
            return Squad(
                id = tag.getUUID("Id"),
                name = tag.getString("Name"),
                color = color,
                order = SquadOrder.byOrdinal(tag.getInt("Order")),
                members = members,
                objective = if (tag.contains("Objective")) NbtUtils.readBlockPos(tag, "Objective").orElse(null) else null,
                focusEntity = if (tag.hasUUID("Focus")) tag.getUUID("Focus") else null,
                owner = tag.getUUID("Owner")
            )
        }
    }
}

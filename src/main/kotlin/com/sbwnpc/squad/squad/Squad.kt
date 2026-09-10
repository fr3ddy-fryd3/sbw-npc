package com.sbwnpc.squad.squad

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import java.util.UUID

class Squad(
    val id: UUID,
    var name: String,
    var color: ChatFormatting,
    var order: SquadOrder,
    var commander: UUID?,
    val members: MutableList<UUID>,
    var objective: BlockPos?,
    val owner: UUID
) {
    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.putString("Name", name)
        tag.putString("Color", color.getName())
        tag.putInt("Order", order.ordinal)
        commander?.let { tag.putUUID("Commander", it) }
        val list = ListTag()
        members.forEach { list.add(net.minecraft.nbt.NbtUtils.createUUID(it)) }
        tag.put("Members", list)
        objective?.let { tag.put("Objective", net.minecraft.nbt.NbtUtils.writeBlockPos(it)) }
        tag.putUUID("Owner", owner)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): Squad {
            val members = mutableListOf<UUID>()
            tag.getList("Members", Tag.TAG_INT_ARRAY.toInt()).forEach { members.add(net.minecraft.nbt.NbtUtils.loadUUID(it)) }
            val color = ChatFormatting.getByName(tag.getString("Color")) ?: ChatFormatting.WHITE
            return Squad(
                id = tag.getUUID("Id"),
                name = tag.getString("Name"),
                color = if (color.isColor) color else ChatFormatting.WHITE,
                order = SquadOrder.byOrdinal(tag.getInt("Order")),
                commander = if (tag.hasUUID("Commander")) tag.getUUID("Commander") else null,
                members = members,
                objective = if (tag.contains("Objective")) net.minecraft.nbt.NbtUtils.readBlockPos(tag, "Objective").orElse(null) else null,
                owner = tag.getUUID("Owner")
            )
        }
    }
}

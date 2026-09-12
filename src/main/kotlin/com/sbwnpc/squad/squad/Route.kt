package com.sbwnpc.squad.squad

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import java.util.UUID

/** A named, ordered list of points a squad on PATROL walks in sequence (see `SquadOrderGoal`). */
class Route(val id: UUID, val owner: UUID, var name: String, val points: List<BlockPos>) {
    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.putUUID("Owner", owner)
        tag.putString("Name", name)
        val list = ListTag()
        points.forEach { p ->
            val pt = CompoundTag()
            pt.putInt("X", p.x)
            pt.putInt("Y", p.y)
            pt.putInt("Z", p.z)
            list.add(pt)
        }
        tag.put("Points", list)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): Route {
            val points = tag.getList("Points", Tag.TAG_COMPOUND.toInt()).map {
                val pt = it as CompoundTag
                BlockPos(pt.getInt("X"), pt.getInt("Y"), pt.getInt("Z"))
            }
            return Route(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getString("Name"), points)
        }
    }
}

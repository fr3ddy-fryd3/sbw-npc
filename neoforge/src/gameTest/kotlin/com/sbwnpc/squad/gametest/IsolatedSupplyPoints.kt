package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.block.entity.SupplyPoints
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel

/** Old GameTest Supply blocks must not turn a no-supply control into a trip across other scenes. */
internal class IsolatedSupplyPoints(level: ServerLevel) : AutoCloseable {
    private val registry = SupplyPoints.get(level)
    private val saved = registry.save(CompoundTag(), level.registryAccess()).getList("Points", Tag.TAG_COMPOUND.toInt())
        .mapNotNull { entry ->
            val tag = entry as CompoundTag
            net.minecraft.nbt.NbtUtils.readBlockPos(tag, "Pos").orElse(null)?.let { pos ->
                pos to if (tag.contains("Faction")) SquadFaction.valueOf(tag.getString("Faction")) else null
            }
        }

    init {
        saved.forEach { registry.remove(it.first) }
    }

    override fun close() {
        saved.forEach { (pos, faction) -> registry.remember(pos, faction) }
    }
}

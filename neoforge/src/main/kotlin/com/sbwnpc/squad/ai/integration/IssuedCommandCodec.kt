package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.ObjectiveKey
import com.sbwnpc.squad.ai.coordination.TacticalOrder
import com.sbwnpc.squad.ai.tactics.CommandSource
import com.sbwnpc.squad.ai.tactics.IssuedCommand
import net.minecraft.nbt.CompoundTag

object IssuedCommandCodec {
    fun save(command: IssuedCommand): CompoundTag =
        CompoundTag().also { tag ->
            tag.putUUID("Id", command.id)
            tag.putUUID("Principal", command.principal)
            tag.putString("Order", command.order.name)
            tag.putString("Source", command.source.name)
            command.goal?.let { goal ->
                tag.putString("Dimension", goal.dimension)
                when (goal) {
                    is ObjectiveKey.Area -> {
                        tag.putString("Goal", "area")
                        tag.putInt("X", goal.x)
                        tag.putInt("Y", goal.y)
                        tag.putInt("Z", goal.z)
                    }

                    is ObjectiveKey.Entity -> {
                        tag.putString("Goal", "entity")
                        tag.putUUID("Target", goal.id)
                    }

                    is ObjectiveKey.Structure -> {
                        tag.putString("Goal", "structure")
                        tag.putUUID("Target", goal.id)
                    }
                }
            }
        }
    fun load(tag: CompoundTag): IssuedCommand? =
        runCatching {
            if (!tag.hasUUID("Id") || !tag.hasUUID("Principal")) return null
            val dimension = tag.getString("Dimension")
            val goal = when (tag.getString("Goal")) {
                "area" -> ObjectiveKey.Area(dimension, tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"))
                "entity" -> ObjectiveKey.Entity(dimension, tag.getUUID("Target"))
                "structure" -> ObjectiveKey.Structure(dimension, tag.getUUID("Target"))
                "" -> null
                else -> return null
            }
            if (goal != null && dimension.isBlank()) return null
            IssuedCommand(
                tag.getUUID("Id"),
                tag.getUUID("Principal"),
                TacticalOrder.valueOf(tag.getString("Order")),
                goal,
                CommandSource.entries.firstOrNull { it.name == tag.getString("Source") } ?: CommandSource.PLAYER,
            )
        }.getOrNull()
}

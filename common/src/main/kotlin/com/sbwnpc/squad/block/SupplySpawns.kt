package com.sbwnpc.squad.block

import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.compat.SavedDataAccess
import com.sbwnpc.squad.compat.VersionedSavedData
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.vehicle.DismountHelper
import net.minecraft.world.level.Level
import net.minecraft.world.level.portal.DimensionTransition
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Players who respawn at a Supply, and which one — like a bed: set from the Supply's screen,
 * replaced by the next bed or anchor the player sets.
 *
 * Kept here rather than as the vanilla respawn point, because vanilla can't tell that a Supply has
 * since been broken: the point would still be free air and the player would keep coming back there.
 * Checked at the moment of respawning instead — the block still there, still serving the player's
 * side, and a safe position above or nearby; otherwise the player comes back at the world spawn, the vanilla respawn
 * point having been cleared when this one was set.
 */
class SupplySpawns : VersionedSavedData() {

    private val spawns = HashMap<UUID, GlobalPos>()

    fun of(player: UUID): GlobalPos? = spawns[player]

    fun set(player: ServerPlayer, level: ServerLevel, pos: BlockPos) {
        spawns[player.uuid] = GlobalPos.of(level.dimension(), pos.immutable())
        setDirty()
        // So that a Supply that can't take the player sends them to the world spawn, not to an
        // older bed. Our own clear, so it doesn't count as the player picking a new spawn below.
        clearingVanilla = true
        try {
            player.setRespawnPosition(Level.OVERWORLD, null, 0f, false, false)
        } finally {
            clearingVanilla = false
        }
    }

    fun clear(player: UUID) {
        if (spawns.remove(player) != null) setDirty()
    }

    override fun write(tag: CompoundTag): CompoundTag {
        spawns.forEach { (uuid, at) ->
            tag.put(
                uuid.toString(),
                CompoundTag().apply {
                    putString("Dim", at.dimension().location().toString())
                    putLong("Pos", at.pos().asLong())
                },
            )
        }
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_supply_spawns"
        internal var clearingVanilla = false

        private fun load(tag: CompoundTag): SupplySpawns {
            val data = SupplySpawns()
            for (key in tag.allKeys) {
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                val entry = tag.getCompound(key)
                val dim = ResourceLocation.tryParse(entry.getString("Dim")) ?: continue
                data.spawns[uuid] = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(entry.getLong("Pos")))
            }
            return data
        }

        fun get(server: MinecraftServer): SupplySpawns = SavedDataAccess.fetch(server.overworld().dataStorage, FILE, ::SupplySpawns, ::load)
    }
}

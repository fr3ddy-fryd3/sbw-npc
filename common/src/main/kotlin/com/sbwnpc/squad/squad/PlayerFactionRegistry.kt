package com.sbwnpc.squad.squad

import com.sbwnpc.squad.compat.SavedDataAccess
import com.sbwnpc.squad.compat.VersionedSavedData
import com.sbwnpc.squad.network.OpenFactionPickPayload
import com.sbwnpc.squad.network.PlayerFactionPayload
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Per-world, per-player faction record. Every player must pick a faction once — the first time
 * they try to actually DO anything with the squad tool (open Recruit, deploy, form a squad) —
 * establishing a "default"/home identity for them.
 *
 * Survival recruitment uses this identity; creative players may deploy another faction.
 * Supply placement always uses the player's own faction, including in creative mode.
 */
class PlayerFactionRegistry : VersionedSavedData() {

    private val factions = HashMap<UUID, SquadFaction>()

    fun get(player: UUID): SquadFaction? = factions[player]

    /** Idempotent — only the first pick ever sticks. */
    fun set(player: UUID, faction: SquadFaction) {
        if (factions.containsKey(player)) return
        factions[player] = faction
        setDirty()
    }

    /** Tells [player]'s client which faction they are on, for the banner shown with the tool. */
    fun sync(player: ServerPlayer) = sendToClient(player, PlayerFactionPayload(factions[player.uuid]?.ordinal ?: -1))

    /** Returns the player's faction, or prompts for the mandatory first choice. */
    fun requireOrPrompt(player: ServerPlayer): SquadFaction? {
        val existing = factions[player.uuid]
        if (existing != null) return existing
        sendToClient(player, OpenFactionPickPayload)
        return null
    }

    /** Re-check at configuration and deployment: a tool can survive a change of game mode. */
    fun recruitmentFaction(player: ServerPlayer, requested: SquadFaction): SquadFaction? {
        val own = requireOrPrompt(player) ?: return null
        return if (player.isCreative) requested else own
    }

    override fun write(tag: CompoundTag): CompoundTag {
        factions.forEach { (uuid, faction) -> tag.putString(uuid.toString(), faction.name) }
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_player_factions"

        private fun load(tag: CompoundTag): PlayerFactionRegistry {
            val reg = PlayerFactionRegistry()
            for (key in tag.allKeys) {
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                readFaction(tag, key)?.let { reg.factions[uuid] = it }
            }
            return reg
        }

        private fun readFaction(tag: CompoundTag, key: String): SquadFaction? =
            when {
                tag.contains(key, Tag.TAG_STRING.toInt()) -> runCatching { SquadFaction.valueOf(tag.getString(key)) }.getOrNull()
                tag.contains(key, Tag.TAG_INT.toInt()) -> SquadFaction.byOrdinal(tag.getInt(key))
                else -> null
            }

        fun get(server: MinecraftServer): PlayerFactionRegistry = SavedDataAccess.fetch(server.overworld().dataStorage, FILE, ::PlayerFactionRegistry, ::load)

        fun get(level: ServerLevel): PlayerFactionRegistry = get(level.server)
    }
}

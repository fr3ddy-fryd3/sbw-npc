package com.sbwnpc.squad.squad

import com.sbwnpc.squad.network.OpenFactionPickPayload
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Per-world, per-player faction record. Every player must pick a faction once — the first time
 * they try to actually DO anything with the squad tool (open Recruit, deploy, form a squad) —
 * establishing a "default"/home identity for them.
 *
 * IMPORTANT — this is NOT currently an enforcement lock: per an explicit user decision, free
 * choice of any of the 8 factions stays fully available during development (e.g. to deploy
 * OPFOR/test squads of a different faction). [requireOrPrompt] only gates on the one-time pick
 * having happened at all — it does not override whatever faction the tool/client is actually
 * using for a given action. The future admin-override design (grant a specific player permission
 * to bypass their own default, once real enforcement exists) is deferred, tracked in PLAN.md.
 */
class PlayerFactionRegistry : SavedData() {

    private val factions = HashMap<UUID, SquadFaction>()

    fun get(player: UUID): SquadFaction? = factions[player]

    /** Idempotent — only the first pick ever sticks. */
    fun set(player: UUID, faction: SquadFaction) {
        if (factions.containsKey(player)) return
        factions[player] = faction
        setDirty()
    }

    /** The player's recorded default faction, or null — and a mandatory pick screen sent to the
     *  client — if they haven't chosen yet. Server-side entry points call this only to gate on
     *  the one-time pick having happened; they should keep using the client/tool's own faction
     *  value for the actual action (see class doc — this is not an enforcement lock right now). */
    fun requireOrPrompt(player: ServerPlayer): SquadFaction? {
        val existing = factions[player.uuid]
        if (existing != null) return existing
        sendToClient(player, OpenFactionPickPayload)
        return null
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        factions.forEach { (uuid, faction) -> tag.putString(uuid.toString(), faction.name) }
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_player_factions"

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): PlayerFactionRegistry {
            val reg = PlayerFactionRegistry()
            for (key in tag.allKeys) {
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                readFaction(tag, key)?.let { reg.factions[uuid] = it }
            }
            return reg
        }

        private fun readFaction(tag: CompoundTag, key: String): SquadFaction? = when {
            tag.contains(key, Tag.TAG_STRING.toInt()) -> runCatching { SquadFaction.valueOf(tag.getString(key)) }.getOrNull()
            tag.contains(key, Tag.TAG_INT.toInt()) -> SquadFaction.byOrdinal(tag.getInt(key))
            else -> null
        }

        fun get(server: MinecraftServer): PlayerFactionRegistry =
            server.overworld().dataStorage.computeIfAbsent(
                SavedData.Factory({ PlayerFactionRegistry() }, ::load, null), FILE
            )

        fun get(level: ServerLevel): PlayerFactionRegistry = get(level.server)
    }
}

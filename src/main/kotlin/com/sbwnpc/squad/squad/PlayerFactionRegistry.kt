package com.sbwnpc.squad.squad

import com.sbwnpc.squad.network.OpenFactionPickPayload
import com.sbwnpc.squad.network.PlayerFactionPayload
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
 * Survival recruitment uses this identity; creative players may deploy another faction.
 * Supply placement always uses the player's own faction, including in creative mode.
 */
class PlayerFactionRegistry : SavedData() {

    private val factions = HashMap<UUID, SquadFaction>()
    private val creativeRecruiters = HashSet<UUID>()

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

    /** Logout records the actual mode even if the owner's barracks chunks are unloaded. */
    fun recordRecruitmentMode(player: ServerPlayer) {
        val changed = if (player.isCreative) creativeRecruiters.add(player.uuid) else creativeRecruiters.remove(player.uuid)
        if (changed) setDirty()
    }

    /** Offline production uses the mode saved at logout; legacy saves default to own-faction recruitment. */
    fun recruitmentProblem(server: MinecraftServer, owner: UUID?, faction: SquadFaction): String? {
        if (owner == null) return "No owner assigned"
        server.playerList.getPlayer(owner)?.let(::recordRecruitmentMode)
        if (owner in creativeRecruiters) return null
        val own = factions[owner] ?: return "Owner must choose a faction"
        return if (faction == own) null else "Survival barracks can recruit only the owner's faction"
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        factions.forEach { (uuid, faction) -> tag.putString(uuid.toString(), faction.name) }
        tag.put("CreativeRecruiters", CompoundTag().apply { creativeRecruiters.forEach { putBoolean(it.toString(), true) } })
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
            val creative = tag.getCompound("CreativeRecruiters")
            for (key in creative.allKeys) {
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                if (uuid in reg.factions && creative.getBoolean(key)) reg.creativeRecruiters.add(uuid)
            }
            return reg
        }

        private fun readFaction(tag: CompoundTag, key: String): SquadFaction? =
            when {
                tag.contains(key, Tag.TAG_STRING.toInt()) -> runCatching { SquadFaction.valueOf(tag.getString(key)) }.getOrNull()
                tag.contains(key, Tag.TAG_INT.toInt()) -> SquadFaction.byOrdinal(tag.getInt(key))
                else -> null
            }

        fun get(server: MinecraftServer): PlayerFactionRegistry =
            server.overworld().dataStorage.computeIfAbsent(
                SavedData.Factory({ PlayerFactionRegistry() }, ::load, null),
                FILE,
            )

        fun get(level: ServerLevel): PlayerFactionRegistry = get(level.server)
    }
}

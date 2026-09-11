package com.sbwnpc.squad.squad

import com.sbwnpc.squad.network.OpenFactionPickPayload
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Per-world, per-player faction lock. Every player must pick a faction once — the first time they
 * try to actually DO anything with the squad tool (open Recruit, deploy, form a squad) — after
 * which the server always uses this value and never again trusts a client-sent faction ordinal.
 * Free choice of any of the 8 factions is still mechanically possible (deferred admin-override
 * design, not this), but it's now a one-time, deliberate pick instead of something that quietly
 * resets per squad.
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

    /** The locked faction, or null — and a mandatory pick screen sent to the client — if they
     *  haven't chosen yet. Every server-side entry point that would assign a faction to something
     *  new (deploy, squad creation, tool config) must go through this instead of trusting a
     *  client-supplied ordinal. */
    fun requireOrPrompt(player: ServerPlayer): SquadFaction? {
        val existing = factions[player.uuid]
        if (existing != null) return existing
        sendToClient(player, OpenFactionPickPayload)
        return null
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        factions.forEach { (uuid, faction) -> tag.putInt(uuid.toString(), faction.ordinal) }
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_player_factions"

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): PlayerFactionRegistry {
            val reg = PlayerFactionRegistry()
            for (key in tag.allKeys) {
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                reg.factions[uuid] = SquadFaction.byOrdinal(tag.getInt(key))
            }
            return reg
        }

        fun get(server: MinecraftServer): PlayerFactionRegistry =
            server.overworld().dataStorage.computeIfAbsent(
                SavedData.Factory({ PlayerFactionRegistry() }, ::load, null), FILE
            )

        fun get(level: ServerLevel): PlayerFactionRegistry = get(level.server)
    }
}

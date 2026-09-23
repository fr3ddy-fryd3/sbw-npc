package com.sbwnpc.squad.team

import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.scores.PlayerTeam

/**
 * Squad faction == a vanilla scoreboard team. One managed `PlayerTeam` per faction, created
 * lazily. Because friend/foe rides on vanilla teams, SBW's own turrets/vehicles
 * (`TowerAI.TeamResolver`) see our NPCs correctly with no changes on their side.
 */
object SquadTeams {
    private const val PREFIX = "sbwnpc_"

    private fun teamName(faction: SquadFaction) = PREFIX + faction.name.lowercase()

    fun getOrCreate(level: ServerLevel, faction: SquadFaction): PlayerTeam {
        val scoreboard = level.scoreboard
        val name = teamName(faction)
        scoreboard.getPlayerTeam(name)?.let { return it }
        return scoreboard.addPlayerTeam(name).apply {
            this.color = faction.accentColor
            displayName = Component.literal(faction.label).withStyle(faction.accentColor)
            isAllowFriendlyFire = false
        }
    }

    fun assign(entity: Entity, faction: SquadFaction) {
        val level = entity.level() as? ServerLevel ?: return
        val team = getOrCreate(level, faction)
        level.scoreboard.addPlayerToTeam(entity.scoreboardName, team)
    }

    fun clear(entity: Entity) {
        val level = entity.level() as? ServerLevel ?: return
        val team = level.scoreboard.getPlayersTeam(entity.scoreboardName) ?: return
        if (team.name.startsWith(PREFIX)) {
            level.scoreboard.removePlayerFromTeam(entity.scoreboardName, team)
        }
    }

    /** The faction an entity's team encodes, purely from the (client-synced) scoreboard team name
     *  — no extra synced entity data needed for e.g. the renderer to pick a skin. */
    fun factionOf(entity: Entity): SquadFaction? {
        val team = entity.team as? PlayerTeam ?: return null
        return factionOfTeamName(team.name)
    }

    // Team name -> faction, memoised. This used to be removePrefix + uppercase + valueOf inside a
    // runCatching on EVERY call — and it's called per candidate in every ally scan, per tick from
    // GunAttackBehaviour, and per FRAME per NPC from NpcRenderer.getTextureLocation on the client.
    // Non-faction team names are cached (as NONE) too, so a world full of vanilla-teamed entities
    // doesn't keep re-parsing them either. Bounded by the number of distinct team names seen.
    // ConcurrentHashMap because in singleplayer the client render thread and the integrated server
    // thread both go through here; it can't hold null, hence the sentinel.
    private val NONE = Any()
    private val factionByTeamName = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun factionOfTeamName(name: String): SquadFaction? {
        val hit = factionByTeamName.computeIfAbsent(name) {
            if (!name.startsWith(PREFIX)) NONE
            else runCatching { SquadFaction.valueOf(name.removePrefix(PREFIX).uppercase()) }.getOrNull() ?: NONE
        }
        return hit as? SquadFaction
    }

    fun clearCache() = factionByTeamName.clear()

    /** Squad friend/foe: both sides need an actual (different) squad faction to be hostile.
     *  Nothing ever puts a player on the scoreboard team itself — a teamless player resolves
     *  through their own chosen faction ([PlayerFactionRegistry]) instead. Anything with no
     *  faction either way (vanilla mobs, a player who never picked one) is neutral, never an
     *  autonomous target. */
    fun isHostile(a: Entity, b: Entity): Boolean {
        if (a === b) return false
        val fa = hostilityFaction(a) ?: return false
        val fb = hostilityFaction(b) ?: return false
        return fa != fb
    }

    /** Whose side [entity] is on for friend-or-foe purposes: an NPC's team, or a player's own pick
     *  ([PlayerFactionRegistry]) — null for anything outside the fight, a creative player included.
     *  [factionOf] alone never answers for a player, since players are never put on a team. */
    fun sideOf(entity: Entity): SquadFaction? = hostilityFaction(entity)

    private fun hostilityFaction(entity: Entity): SquadFaction? {
        factionOf(entity)?.let { return it }
        val player = entity as? Player ?: return null
        // A creative player is observing/building, not part of the fight — never an autonomous
        // target regardless of whatever faction they'd picked.
        if (player.isCreative) return null
        val level = player.level() as? ServerLevel ?: return null
        return PlayerFactionRegistry.get(level).get(player.uuid)
    }
}

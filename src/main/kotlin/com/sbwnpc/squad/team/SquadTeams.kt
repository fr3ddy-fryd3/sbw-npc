package com.sbwnpc.squad.team

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Team

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
        if (!team.name.startsWith(PREFIX)) return null
        return runCatching { SquadFaction.valueOf(team.name.removePrefix(PREFIX).uppercase()) }.getOrNull()
    }

    /** Squad friend/foe: both sides need an actual (different) squad faction to be hostile.
     *  Anything teamless — the owning player included, since nothing ever puts a player on a
     *  squad team — is neutral, never an autonomous target. */
    fun isHostile(a: Entity, b: Entity): Boolean {
        if (a === b) return false
        val ta: Team = a.team ?: return false
        val tb: Team = b.team ?: return false
        return ta !== tb && !a.isAlliedTo(b)
    }
}

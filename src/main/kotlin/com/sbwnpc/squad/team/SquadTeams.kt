package com.sbwnpc.squad.team

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Team

/**
 * Squad colour == a vanilla scoreboard team. One managed `PlayerTeam` per colour, created lazily.
 * Because friend/foe rides on vanilla teams, SBW's own turrets/vehicles (`TowerAI.TeamResolver`)
 * see our NPCs correctly with no changes on their side.
 */
object SquadTeams {
    private const val PREFIX = "sbwnpc_"

    /** 8 distinct, easily-told-apart squad colours. */
    val COLORS: List<ChatFormatting> = listOf(
        ChatFormatting.RED,
        ChatFormatting.BLUE,
        ChatFormatting.GREEN,
        ChatFormatting.YELLOW,
        ChatFormatting.AQUA,
        ChatFormatting.LIGHT_PURPLE,
        ChatFormatting.GOLD,
        ChatFormatting.WHITE,
    )

    fun byOrdinal(i: Int): ChatFormatting = COLORS.getOrElse(i) { COLORS.first() }

    fun ordinalOf(color: ChatFormatting): Int = COLORS.indexOf(color).coerceAtLeast(0)

    private fun teamName(color: ChatFormatting) = PREFIX + color.getName()

    fun getOrCreate(level: ServerLevel, color: ChatFormatting): PlayerTeam {
        val scoreboard = level.scoreboard
        val name = teamName(color)
        scoreboard.getPlayerTeam(name)?.let { return it }
        return scoreboard.addPlayerTeam(name).apply {
            this.color = color
            displayName = Component.literal(color.getName().replaceFirstChar { it.uppercase() } + " Squad")
                .withStyle(color)
            isAllowFriendlyFire = false
        }
    }

    fun assign(entity: Entity, color: ChatFormatting) {
        val level = entity.level() as? ServerLevel ?: return
        val team = getOrCreate(level, color)
        level.scoreboard.addPlayerToTeam(entity.scoreboardName, team)
    }

    fun clear(entity: Entity) {
        val level = entity.level() as? ServerLevel ?: return
        val team = level.scoreboard.getPlayersTeam(entity.scoreboardName) ?: return
        if (team.name.startsWith(PREFIX)) {
            level.scoreboard.removePlayerFromTeam(entity.scoreboardName, team)
        }
    }

    fun colorOf(entity: Entity): ChatFormatting? {
        val team = entity.team as? PlayerTeam ?: return null
        if (!team.name.startsWith(PREFIX)) return null
        return team.color.takeIf { it.isColor }
    }

    /** Squad-colour friend/foe: both sides need an actual (different) squad colour to be hostile.
     *  Anything colourless — the owning player included, since nothing ever puts a player on a
     *  squad team — is neutral, never an autonomous target. */
    fun isHostile(a: Entity, b: Entity): Boolean {
        if (a === b) return false
        val ta: Team = a.team ?: return false
        val tb: Team = b.team ?: return false
        return ta !== tb && !a.isAlliedTo(b)
    }
}

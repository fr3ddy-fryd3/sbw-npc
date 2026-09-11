package com.sbwnpc.squad.npc

import com.sbwnpc.squad.SquadMod.Companion.loc
import net.minecraft.ChatFormatting
import net.minecraft.resources.ResourceLocation

/**
 * What used to be "squad colour" — friend/foe still rides on a vanilla scoreboard team
 * ([com.sbwnpc.squad.team.SquadTeams]), but the team is now keyed by faction (a skin) rather than
 * a bare colour, so different squads are told apart by silhouette in combat, not just a subtle
 * name-tag tint. [accentColor] is kept as a secondary, derived attribute for chat/UI text.
 */
enum class SquadFaction(val label: String, val accentColor: ChatFormatting) {
    PIG("Piggies", ChatFormatting.RED),
    COW("Cows", ChatFormatting.BLUE),
    CREEPER("Creeps", ChatFormatting.GREEN),
    SHEEP("Sheep", ChatFormatting.WHITE),
    PANDA("Pandas", ChatFormatting.YELLOW),
    CAT("Cats", ChatFormatting.GOLD),
    ENDER("Enders", ChatFormatting.LIGHT_PURPLE),
    WITHER("Withers", ChatFormatting.AQUA);

    val texture: ResourceLocation = loc("textures/entity/${name.lowercase()}.png")

    fun next(): SquadFaction = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = PIG
        fun byOrdinal(i: Int): SquadFaction = entries.getOrElse(i) { DEFAULT }
    }
}

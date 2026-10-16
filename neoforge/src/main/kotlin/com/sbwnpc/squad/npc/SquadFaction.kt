package com.sbwnpc.squad.npc

import com.sbwnpc.squad.SquadMod.Companion.loc
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation

/**
 * What used to be "squad colour" — friend/foe still rides on a vanilla scoreboard team
 * ([com.sbwnpc.squad.team.SquadTeams]), but the team is now keyed by faction (a skin) rather than
 * a bare colour, so different squads are told apart by silhouette in combat, not just a subtle
 * name-tag tint. [accentColor] supplies vanilla's limited scoreboard palette; [rgb] and
 * [accentStyle] share the readable faction palette for markers, particles and interface text.
 */
enum class SquadFaction(val label: String, val accentColor: ChatFormatting, val rgb: Int = accentColor.color ?: 0xFFFFFF) {
    PIG("Piggies", ChatFormatting.RED),
    COW("Cows", ChatFormatting.BLUE, 0x80A0FF),
    CREEPER("Creeps", ChatFormatting.GREEN),
    SHEEP("Sheep", ChatFormatting.WHITE),
    PANDA("Pandas", ChatFormatting.YELLOW),
    CAT("Cats", ChatFormatting.GOLD),
    ENDER("Enders", ChatFormatting.LIGHT_PURPLE),
    WITHER("Withers", ChatFormatting.AQUA);

    val accentStyle: Style = Style.EMPTY.withColor(rgb)

    val texture: ResourceLocation = loc("textures/entity/${name.lowercase()}.png")

    fun next(): SquadFaction = entries[(ordinal + 1) % entries.size]

    /** Green (RU 6B47/6B43) rather than sand (US PASGT/IOTV) helmet and vest — for its NPCs and for
     *  its players' Supply kits alike. */
    val greenUniform: Boolean get() = this == CREEPER || this == CAT || this == PIG || this == COW

    companion object {
        val DEFAULT = PIG
        fun byOrdinal(i: Int): SquadFaction = entries.getOrElse(i) { DEFAULT }
    }
}

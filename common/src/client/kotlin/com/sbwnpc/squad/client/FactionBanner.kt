package com.sbwnpc.squad.client

import com.sbwnpc.squad.client.screen.CommandScreen
import com.sbwnpc.squad.client.screen.DiplomacyScreen
import com.sbwnpc.squad.client.screen.RecruitScreen
import com.sbwnpc.squad.client.screen.RoutesScreen
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen

/** The player's own faction at the top of the squad tool's screens — it is easy to lose track of
 *  which side you picked. Fed by [com.sbwnpc.squad.network.PlayerFactionPayload]. */
object FactionBanner {
    private const val TOP = 6
    private const val PADDING = 3

    var faction: SquadFaction? = null

    private fun toolScreen(screen: Screen) =
        when (screen) {
            is CommandScreen, is RoutesScreen, is DiplomacyScreen -> true
            is RecruitScreen -> screen.forTool
            else -> false
        }

    /** Draws the banner over [screen] after its own widgets, for the tool's screens only. */
    fun render(screen: Screen, g: GuiGraphics) {
        if (!toolScreen(screen)) return
        val font = Minecraft.getInstance().font
        val faction = faction
        val text = if (faction == null) "Your faction: not picked" else "Your faction: ${faction.label}"
        val color = 0xFF000000.toInt() or (faction?.rgb ?: 0xAAAAAA)

        val width = font.width(text)
        val x = (screen.width - width) / 2
        g.fill(x - PADDING, TOP - PADDING, x + width + PADDING, TOP + font.lineHeight + PADDING - 1, 0xAA000000.toInt())
        g.drawString(font, text, x, TOP, color)
    }

    fun onLogout() {
        faction = null
    }
}

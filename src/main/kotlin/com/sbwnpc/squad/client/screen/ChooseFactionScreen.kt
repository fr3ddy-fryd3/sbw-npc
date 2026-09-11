package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.ChooseFactionPayload
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Mandatory, one-time faction pick shown the first time a player ever interacts with the squad
 * tool. No Done/Cancel button and ESC is disabled — the only way to close this screen is to pick
 * one of the 8 factions, which locks it in server-side for good (see
 * [com.sbwnpc.squad.squad.PlayerFactionRegistry]).
 */
class ChooseFactionScreen : Screen(Component.literal("Choose Your Faction")) {

    override fun init() {
        val cx = width / 2
        val cols = 4
        val btnW = 84
        val btnH = 20
        val gapX = 8
        val gapY = 8
        val totalW = cols * btnW + (cols - 1) * gapX
        val startX = cx - totalW / 2
        val startY = height / 2 - 30

        SquadFaction.entries.forEachIndexed { i, faction ->
            val col = i % cols
            val row = i / cols
            addRenderableWidget(
                Button.builder(Component.literal(faction.label).withStyle(faction.accentColor)) {
                    PacketDistributor.sendToServer(ChooseFactionPayload(faction.ordinal))
                    onClose()
                }.bounds(startX + col * (btnW + gapX), startY + row * (btnH + gapY), btnW, btnH).build()
            )
        }
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFF)
        g.drawCenteredString(font, Component.literal("Pick your faction — this is permanent"), width / 2, height / 2 - 48, 0xAAAAAA)
    }

    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = false
}

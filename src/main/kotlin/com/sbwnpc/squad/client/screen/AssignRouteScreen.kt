package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.RouteCmdPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/** Picks which of the player's squads a route gets assigned to. Reached from [RoutesScreen]'s
 *  `[Assign]` button; `back` is where "Cancel" returns to (re-showing the same snapshot rather
 *  than round-tripping the server again). */
class AssignRouteScreen(
    private val routeId: String,
    private val rows: List<AssignableSquad>,
    private val back: Screen
) : Screen(Component.literal("Assign Route")) {

    override fun init() {
        val cx = width / 2
        var y = height / 2 - (rows.size * 24) / 2

        for (row in rows) {
            addRenderableWidget(Button.builder(Component.literal(row.name).withStyle(row.faction.accentColor)) {
                PacketDistributor.sendToServer(RouteCmdPayload(RouteCmdPayload.ASSIGN, routeId, row.id))
                onClose()
            }.bounds(cx - 100, y, 200, 20).build())
            y += 24
        }

        y += 12
        addRenderableWidget(Button.builder(Component.literal("Cancel")) {
            Minecraft.getInstance().setScreen(back)
        }.bounds(cx - 100, y, 200, 20).build())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - (rows.size * 24) / 2 - 20, 0xFFFFFF)
        if (rows.isEmpty()) {
            g.drawCenteredString(font, Component.literal("No squads to assign to"), width / 2, height / 2, 0xAAAAAA)
        }
    }

    override fun isPauseScreen() = false
}

package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.RouteCmdPayload
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/** Lists the player's patrol routes, with `+ Add route` at the top to start recording a new one
 *  (server-side state, see `com.sbwnpc.squad.squad.RouteRecording`) and `[Assign]`/`[Delete]` per
 *  row. */
class RoutesScreen(snapshot: CompoundTag) : Screen(Component.literal("Patrol Routes")) {

    private data class RouteRow(val id: String, val name: String, val points: Int)

    private val routes = snapshot.getList("Routes", Tag.TAG_COMPOUND.toInt()).map {
        val t = it as CompoundTag
        RouteRow(t.getString("Id"), t.getString("Name"), t.getInt("Points"))
    }
    private val squads: List<AssignableSquad> = snapshot.getList("Squads", Tag.TAG_COMPOUND.toInt()).map {
        val t = it as CompoundTag
        AssignableSquad(t.getString("Id"), t.getString("Name"), runCatching { SquadFaction.valueOf(t.getString("Faction")) }.getOrDefault(SquadFaction.DEFAULT))
    }

    override fun init() {
        val x = width / 2 - 150
        var y = height / 2 - 90

        addRenderableWidget(Button.builder(Component.literal("+ Add route")) {
            PacketDistributor.sendToServer(RouteCmdPayload(RouteCmdPayload.START_RECORDING, "", ""))
            onClose()
        }.bounds(x, y, 300, 20).build())

        y += 26
        if (routes.isEmpty()) {
            // Just a label — no widget needed, drawn in render().
        }
        for (route in routes) {
            addRenderableWidget(Button.builder(Component.literal("Assign")) {
                Minecraft.getInstance().setScreen(AssignRouteScreen(route.id, squads, this))
            }.bounds(x + 210, y, 60, 20).build())

            addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED)) {
                PacketDistributor.sendToServer(RouteCmdPayload(RouteCmdPayload.DELETE, route.id, ""))
                onClose()
            }.bounds(x + 276, y, 24, 20).build())

            y += 24
        }

        y += 12
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(x, y, 300, 20).build())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 108, 0xFFFFFF)
        val x = width / 2 - 150
        var y = height / 2 - 90 + 26
        if (routes.isEmpty()) {
            g.drawString(font, Component.literal("No routes yet — Add route, then right-click blocks to place points"), x, y + 6, 0xAAAAAA)
        }
        for (route in routes) {
            g.drawString(font, Component.literal("${route.name} (${route.points} pts)"), x, y + 6, -1)
            y += 24
        }
    }

    override fun isPauseScreen() = false
}

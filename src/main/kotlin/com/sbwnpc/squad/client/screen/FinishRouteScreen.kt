package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.RouteCmdPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/** Shown instead of the normal tool GUI while recording a route (air-click is intercepted
 *  server-side, see `ModNetwork`/`RouteRecording`). "Finish" asks for a name; "Cancel" drops the
 *  buffer without saving. */
class FinishRouteScreen(private val pointCount: Int) : Screen(Component.literal("Recording Route")) {

    override fun init() {
        val cx = width / 2
        val y = height / 2 - 10

        addRenderableWidget(Button.builder(Component.literal("Finish ($pointCount points)")) {
            Minecraft.getInstance().setScreen(NameRouteScreen())
        }.bounds(cx - 100, y, 200, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Cancel")) {
            PacketDistributor.sendToServer(RouteCmdPayload(RouteCmdPayload.CANCEL, "", ""))
            onClose()
        }.bounds(cx - 100, y + 24, 200, 20).build())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 34, 0xFFFFFF)
        g.drawCenteredString(font, Component.literal("Right-click blocks to keep adding points"), width / 2, height / 2 - 22, 0xAAAAAA)
    }

    override fun isPauseScreen() = false
}

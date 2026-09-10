package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.SquadCmdPayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

class RenameScreen(private val squadId: String, private val current: String) : Screen(Component.literal("Rename Squad")) {

    private lateinit var box: EditBox

    override fun init() {
        val cx = width / 2
        box = EditBox(font, cx - 100, height / 2 - 10, 200, 20, Component.literal("name"))
        box.value = current
        box.setMaxLength(24)
        addRenderableWidget(box)
        setInitialFocus(box)

        addRenderableWidget(Button.builder(Component.literal("OK")) { confirm() }.bounds(cx - 100, height / 2 + 16, 96, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Cancel")) { onClose() }.bounds(cx + 4, height / 2 + 16, 96, 20).build())
    }

    private fun confirm() {
        val v = box.value.trim()
        if (v.isNotEmpty()) PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.RENAME, squadId, 0, v))
        onClose()
    }

    override fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        if (key == 257 || key == 335) { confirm(); return true } // enter
        return super.keyPressed(key, scan, mods)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 36, 0xFFFFFF)
    }

    override fun isPauseScreen() = false
}

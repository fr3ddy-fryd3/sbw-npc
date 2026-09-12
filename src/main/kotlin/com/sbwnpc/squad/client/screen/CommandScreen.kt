package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.RouteCmdPayload
import com.sbwnpc.squad.network.SquadCmdPayload
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

class CommandScreen(snapshot: CompoundTag) : Screen(Component.literal("Squads")) {

    private data class Row(val id: String, val name: String, val faction: SquadFaction, val members: Int, var order: SquadOrder)

    private val loose = snapshot.getInt("Loose")
    private val rows = snapshot.getList("Squads", Tag.TAG_COMPOUND.toInt()).map {
        val t = it as CompoundTag
        Row(
            t.getString("Id"), t.getString("Name"),
            runCatching { SquadFaction.valueOf(t.getString("Faction")) }.getOrDefault(SquadFaction.DEFAULT),
            t.getInt("Members"), SquadOrder.byOrdinal(t.getInt("Order"))
        )
    }
    private var listTop = 0

    private fun send(action: Int, id: String = "", value: Int = 0, text: String = "") =
        PacketDistributor.sendToServer(SquadCmdPayload(action, id, value, text))

    override fun init() {
        val x = width / 2 - 182
        var y = height / 2 - 74

        val create = Button.builder(Component.literal("Create squad from selection ($loose)")) {
            send(SquadCmdPayload.CREATE); onClose()
        }.bounds(x, y, 364, 20).build()
        create.active = loose > 0
        addRenderableWidget(create)

        y += 26
        listTop = y - 2
        for (row in rows) {
            addRenderableWidget(Button.builder(Component.literal("Order: ${row.order.name}")) {
                row.order = row.order.next()
                it.message = Component.literal("Order: ${row.order.name}")
                send(SquadCmdPayload.SET_ORDER, row.id, row.order.ordinal)
            }.bounds(x + 108, y, 104, 20).build())

            addRenderableWidget(Button.builder(Component.literal("Objective")) {
                send(SquadCmdPayload.ARM_OBJECTIVE, row.id); onClose()
            }.bounds(x + 216, y, 66, 20).build())

            addRenderableWidget(Button.builder(Component.literal("Focus")) {
                send(SquadCmdPayload.ARM_FOCUS, row.id); onClose()
            }.bounds(x + 286, y, 44, 20).build())

            addRenderableWidget(Button.builder(Component.literal("R")) {
                Minecraft.getInstance().setScreen(RenameScreen(row.id, row.name))
            }.bounds(x + 334, y, 14, 20).build())

            addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED)) {
                send(SquadCmdPayload.DISBAND, row.id); onClose()
            }.bounds(x + 350, y, 14, 20).build())

            y += 24
        }

        y += 12
        addRenderableWidget(Button.builder(Component.literal("Routes")) {
            PacketDistributor.sendToServer(RouteCmdPayload(RouteCmdPayload.REQUEST_LIST, "", ""))
        }.bounds(x, y, 176, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(x + 188, y, 176, 20).build())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 96, 0xFFFFFF)
        val x = width / 2 - 182
        if (rows.isEmpty()) {
            g.drawString(font, Component.literal("No squads — select NPCs, then Create"), x, listTop + 6, 0xAAAAAA)
        }
        rows.forEachIndexed { i, row ->
            g.drawString(font, Component.literal("${row.name} (${row.members}) [${row.faction.label}]").withStyle(row.faction.accentColor),
                x, listTop + 4 + i * 24 + 6, -1)
        }
    }

    override fun isPauseScreen() = false
}

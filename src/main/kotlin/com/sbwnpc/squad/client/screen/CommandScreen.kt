package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.SquadCmdPayload
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

class CommandScreen(private val snapshot: CompoundTag) : Screen(Component.literal("Squads")) {

    private data class Row(val id: String, val name: String, val color: ChatFormatting, val members: Int, var order: SquadOrder)

    private val loose = snapshot.getInt("Loose")
    private val rows = snapshot.getList("Squads", Tag.TAG_COMPOUND.toInt()).map {
        val t = it as CompoundTag
        Row(
            t.getString("Id"), t.getString("Name"),
            ChatFormatting.getByName(t.getString("Color"))?.takeIf { c -> c.isColor } ?: ChatFormatting.WHITE,
            t.getInt("Members"), SquadOrder.byOrdinal(t.getInt("Order"))
        )
    }

    override fun init() {
        val cx = width / 2
        var y = height / 2 - 60

        val create = Button.builder(Component.literal("Create squad from selection ($loose)")) {
            PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.CREATE, "", 0)); onClose()
        }.bounds(cx - 150, y, 300, 20).build()
        create.active = loose > 0
        addRenderableWidget(create)

        y += 28
        for (row in rows) {
            addRenderableWidget(
                Button.builder(Component.literal("${row.name} (${row.members})").withStyle(row.color)) {}
                    .bounds(cx - 150, y, 130, 20).build().apply { active = false }
            )
            val orderBtn = Button.builder(orderLabel(row)) {
                row.order = row.order.next()
                it.message = orderLabel(row)
                PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.SET_ORDER, row.id, row.order.ordinal))
            }.bounds(cx - 14, y, 110, 20).build()
            addRenderableWidget(orderBtn)
            addRenderableWidget(
                Button.builder(Component.literal("Disband").withStyle(ChatFormatting.RED)) {
                    PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.DISBAND, row.id, 0)); onClose()
                }.bounds(cx + 100, y, 50, 20).build()
            )
            y += 24
        }

        y += 10
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(cx - 150, y, 300, 20).build())
    }

    private fun orderLabel(row: Row) = Component.literal("Order: ${row.order.name}")

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 80, 0xFFFFFF)
        if (rows.isEmpty()) {
            g.drawCenteredString(font, Component.literal("No squads yet — select NPCs and create one"),
                width / 2, height / 2 - 20, 0xAAAAAA)
        }
    }

    override fun isPauseScreen() = false
}

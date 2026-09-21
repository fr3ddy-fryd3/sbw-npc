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

/**
 * The squad list. Scrolls, because there is no cap on how many squads a player runs any more and
 * the footer buttons used to be pushed off the bottom of the screen by the tenth row.
 *
 * Delete and Disband deliberately do NOT close the screen: the server answers both by re-sending
 * the snapshot ([com.sbwnpc.squad.network.ModNetwork.onSquadCmd]), which lands here as a fresh
 * screen, so managing several squads in a row takes one trip instead of reopening the GUI each
 * time. [scrollMemory] is what keeps that from bouncing the list back to the top.
 */
class CommandScreen(snapshot: CompoundTag) : Screen(Component.literal("Squads")) {

    private data class Row(
        val id: String,
        val name: String,
        val faction: SquadFaction,
        val members: Int,
        var order: SquadOrder,
        val tank: Boolean,
        val mortar: Boolean,
        val gunship: Boolean,
        val transport: Boolean
    )

    private val loose = snapshot.getInt("Loose")
    private val rows = snapshot.getList("Squads", Tag.TAG_COMPOUND.toInt()).map {
        val t = it as CompoundTag
        Row(
            t.getString("Id"), t.getString("Name"),
            runCatching { SquadFaction.valueOf(t.getString("Faction")) }.getOrDefault(SquadFaction.DEFAULT),
            t.getInt("Members"), SquadOrder.byOrdinal(t.getInt("Order")),
            t.getBoolean("Tank"), t.getBoolean("Mortar"),
            t.getBoolean("Gunship"), t.getBoolean("Transport")
        )
    }

    private var scroll = scrollMemory
    private var listTop = 0
    private var visibleRows = 0
    private var listX = 0

    private fun send(action: Int, id: String = "", value: Int = 0, text: String = "") =
        PacketDistributor.sendToServer(SquadCmdPayload(action, id, value, text))

    /** Rows that fit between the Create button and the footer, leaving the title room above. */
    private fun capacity(): Int = ((height - VERTICAL_CHROME) / ROW_HEIGHT).coerceIn(1, MAX_VISIBLE_ROWS)

    override fun init() {
        visibleRows = minOf(rows.size, capacity())
        scroll = scroll.coerceIn(0, maxOf(0, rows.size - visibleRows))

        listX = width / 2 - 182
        val x = listX
        val panelHeight = HEADER_HEIGHT + visibleRows * ROW_HEIGHT + FOOTER_GAP + BUTTON_HEIGHT
        var y = (height - panelHeight) / 2

        val create = Button.builder(Component.literal("Create squad from selection ($loose)")) {
            send(SquadCmdPayload.CREATE); onClose()
        }.bounds(x, y, 364, 20).build()
        create.active = loose > 0
        addRenderableWidget(create)

        y += HEADER_HEIGHT
        listTop = y - 2
        for (row in rows.drop(scroll).take(visibleRows)) {
            addRenderableWidget(Button.builder(Component.literal("Order: ${row.order.name}")) {
                row.order = row.order.next(SquadOrder.availableFor(row.tank, row.mortar, row.gunship, row.transport))
                it.message = Component.literal("Order: ${row.order.name}")
                send(SquadCmdPayload.SET_ORDER, row.id, row.order.ordinal)
            }.bounds(x + 92, y, 90, 20).build())

            addRenderableWidget(Button.builder(Component.literal("Objective")) {
                send(SquadCmdPayload.ARM_OBJECTIVE, row.id); onClose()
            }.bounds(x + 186, y, 58, 20).build())

            addRenderableWidget(Button.builder(Component.literal("Focus")) {
                send(SquadCmdPayload.ARM_FOCUS, row.id); onClose()
            }.bounds(x + 248, y, 44, 20).build())

            addRenderableWidget(Button.builder(Component.literal("R")) {
                Minecraft.getInstance().setScreen(RenameScreen(row.id, row.name))
            }.bounds(x + 296, y, 14, 20).build())

            // Disband/Delete keep the screen up — the server re-sends the list (see class doc).
            addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED)) {
                send(SquadCmdPayload.DISBAND, row.id)
            }.bounds(x + 314, y, 14, 20).build())

            addRenderableWidget(Button.builder(Component.literal("DEL").withStyle(ChatFormatting.DARK_RED)) {
                send(SquadCmdPayload.DELETE_SQUAD, row.id)
            }.bounds(x + 332, y, 32, 20).build())

            y += ROW_HEIGHT
        }

        y += FOOTER_GAP
        addRenderableWidget(Button.builder(Component.literal("Routes")) {
            PacketDistributor.sendToServer(RouteCmdPayload(RouteCmdPayload.REQUEST_LIST, "", ""))
        }.bounds(x, y, 176, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(x + 188, y, 176, 20).build())
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        if (rows.size <= visibleRows) return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)
        val before = scroll
        scroll = (scroll - deltaY.toInt()).coerceIn(0, rows.size - visibleRows)
        if (scroll != before) rebuildWidgets()
        return true
    }

    override fun onClose() {
        scrollMemory = scroll
        super.onClose()
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        val panelHeight = HEADER_HEIGHT + visibleRows * ROW_HEIGHT + FOOTER_GAP + BUTTON_HEIGHT
        val top = (height - panelHeight) / 2
        g.drawCenteredString(font, title, width / 2, top - 22, 0xFFFFFF)
        if (rows.isEmpty()) {
            g.drawString(font, Component.literal("No squads — select NPCs, then Create"), listX, listTop + 6, 0xAAAAAA)
        } else if (rows.size > visibleRows) {
            val shown = "${scroll + 1}-${scroll + visibleRows} of ${rows.size} — scroll to see more"
            g.drawString(font, Component.literal(shown), listX, top - 12, 0xAAAAAA)
        }
        rows.drop(scroll).take(visibleRows).forEachIndexed { i, row ->
            g.drawString(
                font,
                Component.literal("${row.name} (${row.members}) [${row.faction.label}]").withStyle(row.faction.accentColor),
                listX, listTop + 4 + i * ROW_HEIGHT + 6, -1
            )
        }
    }

    override fun isPauseScreen() = false

    private companion object {
        const val ROW_HEIGHT = 24
        const val BUTTON_HEIGHT = 20
        /** Create button plus the gap under it. */
        const val HEADER_HEIGHT = 26
        const val FOOTER_GAP = 12
        const val MAX_VISIBLE_ROWS = 12
        /** Title, Create row, footer and a margin — what the rows cannot use. */
        const val VERTICAL_CHROME = 110

        /** Survives the screen being replaced by a fresh snapshot after a delete/disband, so the
         *  list doesn't jump back to the top under the player's cursor. */
        var scrollMemory = 0
    }
}

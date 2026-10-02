package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.RequestBarracksQueuePayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/** Live server queue, with scrolling for large compositions and shared legacy barracks. */
class BarracksQueueScreen(
    private val parent: RecruitScreen,
    private val pos: BlockPos,
    private var snapshot: CompoundTag
) : Screen(Component.literal("Barracks recruitment queue")) {
    private var refreshTicks = 20
    private var scroll = 0

    override fun init() {
        addRenderableWidget(Button.builder(Component.literal("Back")) { onClose() }
            .bounds(width / 2 - 75, height - 28, 150, 20).build())
    }

    fun updateQueue(pos: BlockPos, data: CompoundTag) {
        if (pos != this.pos) return
        snapshot = data
        parent.updateBarracksQueue(pos, data)
    }

    override fun tick() {
        if (++refreshTicks >= 20) {
            refreshTicks = 0
            PacketDistributor.sendToServer(RequestBarracksQueuePayload(pos))
        }
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF)
        val entries = snapshot.getList("Entries", Tag.TAG_COMPOUND.toInt())
        val seconds = (snapshot.getInt("RemainingTicks") + 19) / 20
        val interval = snapshot.getInt("IntervalTicks") / 20
        val status = if (entries.isEmpty()) "Queue empty" else "${entries.size} waiting | Next: ${seconds}s | Interval: ${interval}s"
        g.drawCenteredString(font, status, width / 2, 30, 0xAAAAAA)
        val reason = snapshot.getString("Reason")
        val reasonLines = font.split(Component.literal(reason), maxOf(100, width - 24))
        var top = 46
        reasonLines.forEach { line -> g.drawString(font, line, 12, top, 0xFFAA55); top += 11 }
        if (reason.isNotEmpty()) top += 4
        val visible = maxOf(1, (height - 42 - top) / 22)
        scroll = scroll.coerceIn(0, maxOf(0, entries.size - visible))
        entries.drop(scroll).take(visible).forEachIndexed { index, raw ->
            val entry = raw as CompoundTag
            val kind = if (entry.getBoolean("Replacement")) "Replacement" else "Initial recruit"
            val line = "${scroll + index + 1}. ${entry.getString("Role")} - ${entry.getString("Squad")}"
            g.drawString(font, font.plainSubstrByWidth(line, width - 24), 12, top + index * 22, 0xFFFFFF)
            val detail = "$kind | ${entry.getString("Faction")}"
            g.drawString(font, font.plainSubstrByWidth(detail, width - 24), 12, top + index * 22 + 10, 0x999999)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        scroll = maxOf(0, scroll - vertical.toInt())
        return true
    }

    override fun onClose() { minecraft?.setScreen(parent) }
    override fun isPauseScreen() = false
}

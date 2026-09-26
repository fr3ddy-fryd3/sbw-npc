package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.DiplomacyCmdPayload
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Where the player's faction stands with each of the others, and the one action that makes sense
 * for each: propose an alliance, agree to one on offer, or — for the player's own alliance — vote to
 * leave it. The rules themselves are the server's (`Diplomacy`); this only shows and asks.
 */
class DiplomacyScreen(snapshot: CompoundTag) : Screen(Component.literal("Diplomacy")) {

    private data class Row(
        val faction: SquadFaction,
        val status: String,
        val truceSeconds: Int,
        val offer: Int,
        val voted: Boolean,
        val members: String
    )

    private val own = runCatching { SquadFaction.valueOf(snapshot.getString("Own")) }.getOrNull()
    private val inAlliance = snapshot.getBoolean("InAlliance")
    private val wantsOut = snapshot.getBoolean("WantsOut")
    private val rows = snapshot.getList("Rows", Tag.TAG_COMPOUND.toInt()).mapNotNull {
        val t = it as CompoundTag
        val faction = runCatching { SquadFaction.valueOf(t.getString("Faction")) }.getOrNull() ?: return@mapNotNull null
        Row(
            faction, t.getString("Status"), t.getInt("TruceSeconds"),
            t.getInt("Offer"), t.getBoolean("Voted"), t.getString("Members")
        )
    }

    private fun send(action: Int, faction: Int = 0, proposal: Int = 0) =
        PacketDistributor.sendToServer(DiplomacyCmdPayload(action, faction, proposal))

    private val left get() = width / 2 - WIDTH / 2
    private val top get() = height / 2 - (rows.size * ROW_HEIGHT + 60) / 2

    override fun init() {
        var y = top + 20
        for (row in rows) {
            val button = when {
                row.status == "ALLY" -> null
                row.offer != 0 && row.voted -> Button.builder(Component.literal("Voted")) {}
                    .tooltip(Tooltip.create(Component.literal("Waiting for the others: ${row.members}")))
                    .build().also { it.active = false }
                row.offer != 0 -> Button.builder(Component.literal("Accept").withStyle(ChatFormatting.GREEN)) {
                    send(DiplomacyCmdPayload.ACCEPT, proposal = row.offer)
                }.tooltip(Tooltip.create(Component.literal("Alliance of ${row.members}"))).build()
                else -> Button.builder(Component.literal("Propose")) {
                    send(DiplomacyCmdPayload.PROPOSE, faction = row.faction.ordinal)
                }.tooltip(Tooltip.create(Component.literal("Offer ${row.faction.label} an alliance"))).build()
            }
            button?.let {
                it.setRectangle(BUTTON_WIDTH, 20, left + WIDTH - BUTTON_WIDTH, y)
                addRenderableWidget(it)
            }
            y += ROW_HEIGHT
        }
        y += 8
        if (inAlliance) {
            val label = if (wantsOut) "Stay in the alliance" else "Vote to leave the alliance"
            addRenderableWidget(Button.builder(Component.literal(label).withStyle(ChatFormatting.GOLD)) {
                send(DiplomacyCmdPayload.TOGGLE_LEAVE)
            }.tooltip(Tooltip.create(Component.literal("Your faction leaves once at least half of its players online want out")))
                .bounds(left, y, WIDTH, 20).build())
            y += 24
        }
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(left, y, WIDTH, 20).build())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        val header = if (own == null) Component.literal("Pick a faction first")
            else Component.literal("Diplomacy — ").append(Component.literal(own.label).withStyle(own.accentColor))
        g.drawCenteredString(font, header, width / 2, top, 0xFFFFFF)
        var y = top + 20
        for (row in rows) {
            g.drawString(font, Component.literal(row.faction.label).withStyle(row.faction.accentColor), left, y + 6, -1)
            g.drawString(font, statusText(row), left + 90, y + 6, -1)
            y += ROW_HEIGHT
        }
    }

    private fun statusText(row: Row): Component = when {
        row.status == "ALLY" -> Component.literal("Ally").withStyle(ChatFormatting.GREEN)
        row.offer != 0 -> Component.literal("Alliance offered").withStyle(ChatFormatting.YELLOW)
        row.status == "TRUCE" -> Component.literal("Truce, ${row.truceSeconds / 60}:${"%02d".format(row.truceSeconds % 60)}")
            .withStyle(ChatFormatting.GOLD)
        else -> Component.literal("Hostile").withStyle(ChatFormatting.RED)
    }

    override fun isPauseScreen() = false

    private companion object {
        const val WIDTH = 300
        const val BUTTON_WIDTH = 80
        const val ROW_HEIGHT = 24
    }
}

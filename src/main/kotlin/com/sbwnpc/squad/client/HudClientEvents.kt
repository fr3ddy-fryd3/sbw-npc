package com.sbwnpc.squad.client

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent

/** Input polling + rendering for the quick-command HUD ([HudOverlayState]/[HudKeys]). Deliberately
 *  not a [net.minecraft.client.gui.screens.Screen] — the whole point is that it never grabs
 *  keyboard/mouse focus, so movement and aiming keep working while it's open. */
@EventBusSubscriber(Dist.CLIENT)
object HudClientEvents {

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        while (HudKeys.TOGGLE.consumeClick()) HudOverlayState.toggle()

        if (!HudOverlayState.isOpen) {
            // Drain so a click made while closed can't carry over as a phantom slot-pick the
            // instant the overlay opens.
            HudKeys.SLOTS.forEach { it.consumeClick() }
            HudKeys.SELECT_ALL.consumeClick()
            return
        }
        HudKeys.SLOTS.forEachIndexed { i, key -> if (key.consumeClick()) HudOverlayState.pickSlot(i) }
        if (HudKeys.SELECT_ALL.consumeClick()) HudOverlayState.selectAll()
    }

    private const val PANEL_WIDTH = 150
    private const val PADDING = 5

    @SubscribeEvent
    fun onRenderGui(event: RenderGuiEvent.Post) {
        if (!HudOverlayState.isOpen) return
        val mc = Minecraft.getInstance()
        val font = mc.font
        val g = event.guiGraphics
        val lineHeight = font.lineHeight + 2

        val header: String
        val lines: List<Pair<String, Int>>
        when (HudOverlayState.mode) {
            HudOverlayState.Mode.SQUAD_LIST -> {
                if (HudOverlayState.rows.isEmpty()) return
                header = "Squads"
                lines = HudOverlayState.rows.mapIndexed { i, row -> "${i + 1}. ${row.name} (${row.members}) [${row.faction.label}]" to argb(row.faction.accentColor) } +
                    listOf("[0] Order ALL" to 0xAAAAAA)
            }
            HudOverlayState.Mode.ORDERS -> {
                header = if (HudOverlayState.selectedAll) "ALL SQUADS (${HudOverlayState.rows.size})"
                else HudOverlayState.selected?.name ?: return
                lines = SquadOrder.entries.mapIndexed { i, order -> "${i + 1}. ${order.name}" to 0xFFFFFF }
            }
        }

        val height = (lines.size + 1) * lineHeight + PADDING * 2
        val x = 8
        val y = (mc.window.guiScaledHeight - height) / 2

        g.fill(x, y, x + PANEL_WIDTH, y + height, 0xAA000000.toInt())
        g.drawString(font, header, x + PADDING, y + PADDING, 0xFFFFFF)
        lines.forEachIndexed { i, (text, color) ->
            g.drawString(font, text, x + PADDING, y + PADDING + (i + 1) * lineHeight, color)
        }
    }

    private fun argb(color: ChatFormatting): Int = 0xFF000000.toInt() or (color.color ?: 0xFFFFFF)
}

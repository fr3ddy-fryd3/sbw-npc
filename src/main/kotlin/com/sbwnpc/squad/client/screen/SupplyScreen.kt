package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.SupplyCmdPayload
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * What a player can do at a Supply of their side: take a kit, or make it their respawn point. Its
 * squads' NPCs need none of this — the block tops them up on its own. The rules are the server's (`SupplyBlock`, `SupplySpawns`); this only shows and asks.
 */
class SupplyScreen(private val pos: BlockPos, snapshot: CompoundTag) : Screen(Component.literal("Supply")) {

    private class Kit(val name: String, val contents: String)

    private val side = snapshot.getString("Side")
    private val spawnHere = snapshot.getBoolean("SpawnHere")
    private val radius = snapshot.getInt("Radius")
    private val kits = snapshot.getList("Kits", Tag.TAG_COMPOUND.toInt()).map {
        val t = it as CompoundTag
        Kit(t.getString("Name"), t.getString("Contents"))
    }

    private fun send(action: Int, kit: Int = 0) = PacketDistributor.sendToServer(SupplyCmdPayload(pos, action, kit))

    private val kitRows get() = (kits.size + 1) / 2
    private val left get() = width / 2 - WIDTH / 2
    private val top get() = height / 2 - (40 + 16 + kitRows * 24 + 8 + 24 + 24) / 2

    override fun init() {
        var y = top + 40 + 16
        val half = (WIDTH - 4) / 2
        kits.forEachIndexed { i, kit ->
            val x = if (i % 2 == 0) left else left + half + 4
            addRenderableWidget(Button.builder(Component.literal(kit.name)) {
                send(SupplyCmdPayload.TAKE_KIT, i)
            }.tooltip(Tooltip.create(Component.literal(kit.contents + "\n\nOnly what you don't already carry")))
                .bounds(x, y + (i / 2) * 24, half, 20).build())
        }
        y += kitRows * 24 + 8
        val spawn = if (spawnHere) Component.literal("You respawn here").withStyle(ChatFormatting.GREEN)
            else Component.literal("Respawn here")
        addRenderableWidget(Button.builder(spawn) { send(SupplyCmdPayload.SET_SPAWN) }
            .tooltip(Tooltip.create(Component.literal(
                "Like a bed: you come back here while this Supply stands, serves your side and has room on top. " +
                    "Otherwise at the world spawn. A bed or anchor replaces it."
            )))
            .bounds(left, y, WIDTH, 20).build()
            .also { it.active = !spawnHere })
        y += 24
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(left, y, WIDTH, 20).build())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, Component.literal("Supply — $side"), width / 2, top, 0xFFFFFF)
        g.drawCenteredString(
            font, Component.literal("Your side's NPCs within $radius blocks are resupplied on their own")
                .withStyle(ChatFormatting.GRAY),
            width / 2, top + 14, 0xFFFFFF
        )
        g.drawString(font, Component.literal("Kits").withStyle(ChatFormatting.GRAY), left, top + 40 + 4, -1)
    }

    override fun isPauseScreen() = false

    private companion object {
        const val WIDTH = 240
    }
}

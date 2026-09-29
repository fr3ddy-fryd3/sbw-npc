package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.SupplyCmdPayload
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * What a player can do at a Supply of their side: take a kit, or make it their respawn point. Its
 * squads' NPCs need none of this — the block tops them up on its own. The rules are the server's
 * (`SupplyBlock`, `SupplySpawns`); this only shows and asks.
 *
 * Kits are flicked through with the arrows, one class at a time; each of its weapons is a button that
 * takes the kit with that weapon. The list shows what comes with the weapon under the mouse.
 */
class SupplyScreen(private val pos: BlockPos, snapshot: CompoundTag) : Screen(Component.literal("Supply")) {

    private class Option(val weapon: ItemStack, val items: List<Pair<ItemStack, Int>>)
    private class Kit(val name: String, val options: List<Option>)

    private val side = snapshot.getString("Side")
    private val spawnHere = snapshot.getBoolean("SpawnHere")
    private val radius = snapshot.getInt("Radius")
    private val kits = snapshot.getList("Kits", Tag.TAG_COMPOUND.toInt()).map { k ->
        val kit = k as CompoundTag
        Kit(kit.getString("Name"), kit.getList("Options", Tag.TAG_COMPOUND.toInt()).map { o ->
            val option = o as CompoundTag
            Option(
                stackOf(option.getString("Weapon")),
                option.getList("Items", Tag.TAG_COMPOUND.toInt()).map {
                    val item = it as CompoundTag
                    stackOf(item.getString("Item")) to item.getInt("Count")
                }
            )
        })
    }

    private val weaponButtons = mutableListOf<Pair<Button, Option>>()

    private fun send(action: Int, kit: Int = 0, option: Int = 0) =
        PacketDistributor.sendToServer(SupplyCmdPayload(pos, action, kit, option))

    private val kit get() = kits.getOrNull(selected.coerceIn(0, (kits.size - 1).coerceAtLeast(0)))
    private val left get() = width / 2 - WIDTH / 2
    private val top get() = height / 2 - HEIGHT / 2
    private val listTop get() = top + 62

    override fun init() {
        weaponButtons.clear()
        addRenderableWidget(Button.builder(Component.literal("<")) { flip(-1) }.bounds(left, top + 36, 20, 20).build())
        addRenderableWidget(Button.builder(Component.literal(">")) { flip(1) }.bounds(left + WIDTH - 20, top + 36, 20, 20).build())

        val options = kit?.options.orEmpty()
        val y = listTop + LIST_HEIGHT
        if (options.isNotEmpty()) {
            val w = (WIDTH - (options.size - 1) * 4) / options.size
            options.forEachIndexed { i, option ->
                val button = Button.builder(option.weapon.hoverName) { send(SupplyCmdPayload.TAKE_KIT, selected, i) }
                    .tooltip(Tooltip.create(Component.literal("Take the kit with this weapon — only what you don't already have")))
                    .bounds(left + i * (w + 4), y, w, 20).build()
                addRenderableWidget(button)
                weaponButtons += button to option
            }
        }

        val spawn = if (spawnHere) Component.literal("You respawn here").withStyle(ChatFormatting.GREEN)
            else Component.literal("Respawn here")
        addRenderableWidget(Button.builder(spawn) { send(SupplyCmdPayload.SET_SPAWN) }
            .tooltip(Tooltip.create(Component.literal(
                "Like a bed: you come back here while this Supply stands, serves your side and has room on top. " +
                    "Otherwise at the world spawn. A bed or anchor replaces it."
            )))
            .bounds(left, y + 32, WIDTH, 20).build()
            .also { it.active = !spawnHere })
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(left, y + 56, WIDTH, 20).build())
    }

    private fun flip(step: Int) {
        if (kits.isEmpty()) return
        selected = Math.floorMod(selected + step, kits.size)
        rebuildWidgets()
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, Component.literal("Supply — $side"), width / 2, top, 0xFFFFFF)
        g.drawCenteredString(
            font, Component.literal("Your side's NPCs within $radius blocks are resupplied on their own")
                .withStyle(ChatFormatting.GRAY),
            width / 2, top + 14, 0xFFFFFF
        )
        val kit = kit ?: return
        g.drawCenteredString(font, Component.literal(kit.name).withStyle(ChatFormatting.YELLOW), width / 2, top + 42, 0xFFFFFF)

        // What comes with the weapon under the mouse, or with the first one.
        val shown = weaponButtons.firstOrNull { it.first.isHovered }?.second ?: kit.options.firstOrNull() ?: return
        var y = listTop
        g.drawString(font, shown.weapon.hoverName, left + 4, y, 0xFFFFFF)
        y += LINE
        for ((stack, count) in shown.items) {
            g.drawString(font, Component.literal("${count}× ").append(stack.hoverName).withStyle(ChatFormatting.GRAY), left + 4, y, -1)
            y += LINE
        }
        g.drawString(font, Component.literal("Helmet and vest of your side").withStyle(ChatFormatting.GRAY), left + 4, y, -1)
    }

    override fun isPauseScreen() = false

    private companion object {
        const val WIDTH = 240
        const val LINE = 11
        /** Room for the longest kit: the weapon, seven lines of kit, the uniform. */
        const val LIST_HEIGHT = LINE * 9 + 6
        const val HEIGHT = 62 + LIST_HEIGHT + 76

        /** The class last looked at, kept while the screen is refreshed after taking a kit. */
        var selected = 0

        fun stackOf(id: String): ItemStack =
            ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
                ?.let { ItemStack(it) } ?: ItemStack.EMPTY
    }
}

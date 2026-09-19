package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.ConfigureToolPayload
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

class RecruitScreen(stack: ItemStack) : Screen(Component.literal("Deploy Config")) {

    private val cfg = SquadToolItem.readConfig(stack)
        ?: SquadToolItem.Config(NpcClass.DEFAULT, NpcRank.DEFAULT, SquadFaction.DEFAULT, SquadPreset.DEFAULT)
    private var preset = cfg.preset
    private var cls = cfg.cls
    private var rank = cfg.rank
    private var faction = cfg.faction
    private var vehicle = cfg.vehicle
    private var vehicleModel = cfg.vehicleModel
    private var tankModel = cfg.tankModel

    private lateinit var classBtn: Button
    private var presetRowY = 0

    override fun init() {
        val cx = width / 2
        var y = height / 2 - 62

        // Preset is arrows-only (per user request) — the label between them is plain text, not a
        // button, so clicking it does nothing. Every other row below still cycles on its own click.
        presetRowY = y
        val arrowW = 20
        addRenderableWidget(Button.builder(Component.literal("<")) {
            preset = preset.previous()
            classBtn.active = preset == SquadPreset.SINGLE
            push()
            rebuildWidgets()
        }.bounds(cx - 100, y, arrowW, 20).build())
        addRenderableWidget(Button.builder(Component.literal(">")) {
            preset = preset.next()
            classBtn.active = preset == SquadPreset.SINGLE
            push()
            rebuildWidgets()
        }.bounds(cx + 100 - arrowW, y, arrowW, 20).build())

        y += 24
        // Vehicle options live between Deploy and Class, and only for the presets they apply to —
        // FIVE/SEVEN can spawn one alongside the squad, T90_CREW picks which tank the crew rides.
        when (preset) {
            SquadPreset.FIVE, SquadPreset.SEVEN -> {
                addRenderableWidget(
                    Checkbox.builder(Component.literal("Spawn with vehicle"), font)
                        .pos(cx - 100, y)
                        .selected(vehicle)
                        .onValueChange { _, v -> vehicle = v; push(); rebuildWidgets() }
                        .build()
                )
                y += 24
                if (vehicle) {
                    if (preset == SquadPreset.FIVE) {
                        addRenderableWidget(Button.builder(vehicleModelLabel()) {
                            vehicleModel = vehicleModel.nextTransport(); it.message = vehicleModelLabel(); push()
                        }.bounds(cx - 100, y, 200, 20).build())
                    } else {
                        // SEVEN has no choice — BMP-2 only. Shown, not clickable, for visibility.
                        addRenderableWidget(Button.builder(Component.literal("Vehicle: ${TransportVehicle.BMP_2.label}").withStyle(ChatFormatting.GOLD)) {}
                            .bounds(cx - 100, y, 200, 20).build()).active = false
                    }
                    y += 24
                }
            }
            SquadPreset.T90_CREW -> {
                addRenderableWidget(Button.builder(tankModelLabel()) {
                    tankModel = tankModel.next(); it.message = tankModelLabel(); push()
                }.bounds(cx - 100, y, 200, 20).build())
                y += 24
            }
            else -> Unit
        }

        classBtn = Button.builder(classLabel()) {
            cls = cls.next(); it.message = classLabel(); push()
        }.bounds(cx - 100, y, 200, 20).build()
        classBtn.active = preset == SquadPreset.SINGLE
        addRenderableWidget(classBtn)

        y += 24
        addRenderableWidget(Button.builder(rankLabel()) {
            rank = rank.next(); it.message = rankLabel(); push()
        }.bounds(cx - 100, y, 200, 20).build())

        y += 24
        // Free choice of ANY faction stays available during development, per the user's explicit
        // call — the mandatory one-time picker (PlayerFactionRegistry/ChooseFactionScreen) only
        // records a default identity, it doesn't restrict this. Locking it down for real is a
        // deferred future step (server-admin override permission), not implemented yet.
        addRenderableWidget(Button.builder(factionLabel()) {
            faction = faction.next()
            it.message = factionLabel(); push()
        }.bounds(cx - 100, y, 200, 20).build())

        y += 34
        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }.bounds(cx - 100, y, 200, 20).build())
    }

    private fun presetLabel() = Component.literal("Deploy: ${preset.label}").withStyle(ChatFormatting.WHITE)
    private fun classLabel() = Component.literal("Class: ${cls.name}").withStyle(ChatFormatting.GOLD)
    private fun rankLabel() = Component.literal("Rank: ${rank.name}").withStyle(ChatFormatting.AQUA)
    private fun factionLabel() = Component.literal("Faction: ${faction.label}").withStyle(faction.accentColor)
    private fun vehicleModelLabel() = Component.literal("Vehicle: ${vehicleModel.label}").withStyle(ChatFormatting.GOLD)
    private fun tankModelLabel() = Component.literal("Tank: ${tankModel.label}").withStyle(ChatFormatting.GOLD)

    private fun push() {
        PacketDistributor.sendToServer(
            ConfigureToolPayload(
                cls.ordinal, rank.ordinal, faction.ordinal, preset.ordinal,
                vehicle, vehicleModel.ordinal, tankModel.ordinal
            )
        )
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 82, 0xFFFFFF)
        g.drawCenteredString(font, presetLabel(), width / 2, presetRowY + 6, 0xFFFFFF)
    }

    override fun isPauseScreen() = false
}

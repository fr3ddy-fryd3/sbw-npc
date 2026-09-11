package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.ConfigureToolPayload
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
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

    private lateinit var classBtn: Button

    override fun init() {
        val cx = width / 2
        var y = height / 2 - 62

        addRenderableWidget(Button.builder(presetLabel()) {
            preset = preset.next(); it.message = presetLabel()
            classBtn.active = preset == SquadPreset.SINGLE
            push()
        }.bounds(cx - 100, y, 200, 20).build())

        y += 24
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

    private fun push() {
        PacketDistributor.sendToServer(ConfigureToolPayload(cls.ordinal, rank.ordinal, faction.ordinal, preset.ordinal))
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 82, 0xFFFFFF)
    }

    override fun isPauseScreen() = false
}

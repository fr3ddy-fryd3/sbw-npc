package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.ConfigureToolPayload
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

class RecruitScreen(stack: ItemStack) : Screen(Component.literal("Deploy Config")) {

    private val cfg = SquadToolItem.readConfig(stack) ?: SquadToolItem.Config(NpcClass.DEFAULT, NpcRank.DEFAULT, SquadTeams.COLORS.first())
    private var cls = cfg.cls
    private var rank = cfg.rank
    private var color = cfg.color

    private lateinit var classBtn: Button
    private lateinit var rankBtn: Button
    private lateinit var colorBtn: Button

    override fun init() {
        val cx = width / 2
        var y = height / 2 - 50

        classBtn = Button.builder(classLabel()) {
            cls = cls.next(); it.message = classLabel(); push()
        }.bounds(cx - 100, y, 200, 20).build()
        addRenderableWidget(classBtn)

        y += 24
        rankBtn = Button.builder(rankLabel()) {
            rank = rank.next(); it.message = rankLabel(); push()
        }.bounds(cx - 100, y, 200, 20).build()
        addRenderableWidget(rankBtn)

        y += 24
        colorBtn = Button.builder(colorLabel()) {
            color = SquadTeams.COLORS[(SquadTeams.ordinalOf(color) + 1) % SquadTeams.COLORS.size]
            it.message = colorLabel(); push()
        }.bounds(cx - 100, y, 200, 20).build()
        addRenderableWidget(colorBtn)

        y += 34
        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }.bounds(cx - 100, y, 200, 20).build())
    }

    private fun classLabel() = Component.literal("Class: ${cls.name}").withStyle(ChatFormatting.GOLD)
    private fun rankLabel() = Component.literal("Rank: ${rank.name}").withStyle(ChatFormatting.AQUA)
    private fun colorLabel() = Component.literal("Colour: ${color.getName()}").withStyle(color)

    private fun push() {
        PacketDistributor.sendToServer(ConfigureToolPayload(cls.ordinal, rank.ordinal, SquadTeams.ordinalOf(color)))
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 70, 0xFFFFFF)
    }

    override fun isPauseScreen() = false
}

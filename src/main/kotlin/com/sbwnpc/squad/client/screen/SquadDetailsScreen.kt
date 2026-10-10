package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.network.SquadCmdPayload
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/** A live view of entity-owned progression; unloaded soldiers are explicitly unavailable. */
class SquadDetailsScreen(private var snapshot: CompoundTag) : Screen(Component.literal("Squad")) {
    val squadId: String get() = snapshot.getString("Id")
    private val members get() = snapshot.getList("Members", Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }
    private val candidates get() = snapshot.getList("MergeCandidates", Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }
    private var destination = candidates.firstOrNull()?.getString("Id")
    private var scroll = 0
    private var refreshTicks = 0
    private var left = 0
    private var top = 0
    private var panelWidth = 0
    private var tableTop = 0
    private var visible = 0
    private lateinit var orderButton: Button

    private fun send(action: Int, value: Int = 0, text: String = "") = PacketDistributor.sendToServer(SquadCmdPayload(action, squadId, value, text))
    private fun order() = SquadOrder.byOrdinal(snapshot.getInt("Order"))
    private fun orderLabel() = Component.literal("Order: ${order().name}")
    private fun chosen() = candidates.firstOrNull { it.getString("Id") == destination }
    private fun destinationLabel() = Component.literal(chosen()?.let { "Into: ${it.getString("Name")} (${it.getInt("Members")})" } ?: "No merge destination")

    fun update(data: CompoundTag) {
        val previous = candidates
        snapshot = data
        scroll = scroll.coerceIn(0, maxOf(0, members.size - visible))
        if (candidates.none { it.getString("Id") == destination }) destination = candidates.firstOrNull()?.getString("Id")
        if (previous != candidates) {
            rebuildWidgets()
        } else if (::orderButton.isInitialized) {
            orderButton.message = orderLabel()
        }
    }

    override fun init() {
        panelWidth = minOf(600, width - 24)
        left = (width - panelWidth) / 2
        visible = minOf(members.size, ((height - 196) / ROW_HEIGHT).coerceAtLeast(1))
        scroll = scroll.coerceIn(0, maxOf(0, members.size - visible))
        val panelHeight = 132 + visible * ROW_HEIGHT
        top = (height - panelHeight) / 2
        tableTop = top + 66
        var y = tableTop + visible * ROW_HEIGHT + 10
        val buttonWidth = (panelWidth - 8) / 3
        orderButton = addRenderableWidget(
            Button.builder(orderLabel()) {
                val next = order().next(SquadOrder.availableFor(snapshot.getBoolean("Tank"), snapshot.getBoolean("Mortar"), snapshot.getBoolean("Gunship"), snapshot.getBoolean("Transport")))
                snapshot.putInt("Order", next.ordinal)
                it.message = orderLabel()
                send(SquadCmdPayload.SET_ORDER, next.ordinal)
            }.bounds(left, y, buttonWidth, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Objective")) {
                send(SquadCmdPayload.ARM_OBJECTIVE)
                super.onClose()
            }.bounds(left + buttonWidth + 4, y, buttonWidth, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Focus")) {
                send(SquadCmdPayload.ARM_FOCUS)
                super.onClose()
            }.bounds(left + (buttonWidth + 4) * 2, y, buttonWidth, 20).build(),
        )
        y += 24
        val pickerWidth = panelWidth / 2
        val picker = addRenderableWidget(
            Button.builder(destinationLabel()) {
                val index = candidates.indexOfFirst { it.getString("Id") == destination }
                destination = candidates[(index + 1) % candidates.size].getString("Id")
                it.message = destinationLabel()
            }.bounds(left, y, pickerWidth, 20)
                .tooltip(Tooltip.create(Component.literal(snapshot.getString("MergeNote")))).build(),
        )
        picker.active = candidates.isNotEmpty()
        val merge = addRenderableWidget(
            Button.builder(Component.literal("Merge")) { confirmMerge() }
                .bounds(left + pickerWidth + 4, y, (panelWidth - pickerWidth - 8) / 2, 20)
                .tooltip(Tooltip.create(Component.literal(snapshot.getString("MergeNote")))).build(),
        )
        merge.active = candidates.isNotEmpty()
        addRenderableWidget(
            Button.builder(Component.literal("Back")) { onClose() }
                .bounds(left + pickerWidth + 8 + (panelWidth - pickerWidth - 8) / 2, y, (panelWidth - pickerWidth - 8) / 2, 20).build(),
        )
    }

    private fun confirmMerge() {
        val target = chosen() ?: return
        val id = target.getString("Id")
        minecraft?.setScreen(
            ConfirmScreen(
                { accepted ->
                    minecraft?.setScreen(this)
                    if (accepted) send(SquadCmdPayload.MERGE, text = id)
                },
                Component.literal("Merge ${snapshot.getString("Name")} into ${target.getString("Name")}?"),
                Component.literal("Soldiers keep their names, rank, experience and kit. ${target.getString("Name")} keeps its ${target.getString("Order")} order and objective."),
            ),
        )
    }

    override fun tick() {
        super.tick()
        if (++refreshTicks >= 40) {
            refreshTicks = 0
            send(SquadCmdPayload.REQUEST_DETAILS, value = 1)
        }
    }

    override fun onClose() {
        PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.REQUEST_LIST, "", 0, ""))
        super.onClose()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        scroll = (scroll - deltaY.toInt()).coerceIn(0, maxOf(0, members.size - visible))
        return true
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, Component.literal("${snapshot.getString("Name")} — ${snapshot.getString("Faction")} (${members.size})"), width / 2, top - 16, 0xFFFFFF)
        val tactic = if (snapshot.contains("Tactic")) "${snapshot.getString("Tactic")} / ${snapshot.getString("Phase")}" else "Following order"
        line(g, "${order().name}: $tactic", top, 0xFFFFFF)
        val home = if (snapshot.contains("Barracks")) " / Barracks assigned" else ""
        line(g, "Objective: ${snapshot.getString("Objective").ifEmpty { "not set" }}$home", top + 13, 0xBBBBBB)
        val loaded = members.filter { it.getBoolean("Loaded") }
        line(g, "Experience: ${loaded.sumOf { it.getDouble("Experience") }.toInt()} / ${loaded.size} loaded soldiers", top + 26, 0x99DDFF)
        val headers = listOf("Name", "Role", "Rank", "XP / next", "HP", "Ammo")
        headers.forEachIndexed { i, label -> g.drawString(font, label, column(i), top + 50, 0xAAAAAA) }
        members.drop(scroll).take(visible).forEachIndexed { index, row ->
            val y = tableTop + index * ROW_HEIGHT
            if (!row.getBoolean("Loaded")) {
                line(g, "${row.getUUID("Id").toString().take(8)} — unloaded; soldier data unavailable", y, 0x777777)
                return@forEachIndexed
            }
            val xp = row.getDouble("Experience").toInt()
            val next = if (row.contains("Next")) row.getDouble("Next").toInt().toString() else "max"
            val values = listOf(row.getString("Name"), row.getString("Role"), row.getString("Rank"), "$xp/$next", "${row.getFloat("Health").toInt()}/${row.getFloat("MaxHealth").toInt()}", "${row.getInt("PrimaryAmmo")}/${row.getInt("PistolAmmo")}")
            values.forEachIndexed { col, value ->
                val end = if (col == 5) left + panelWidth else column(col + 1)
                g.drawString(font, font.plainSubstrByWidth(value, end - column(col) - 4), column(col), y, if (row.getFloat("Health") < row.getFloat("MaxHealth") / 2) 0xFFAA88 else 0xFFFFFF)
            }
            if (mouseX in left until left + panelWidth && mouseY in y until y + ROW_HEIGHT) {
                g.renderComponentTooltip(
                    font,
                    listOf(
                        Component.literal("${row.getString("Name")} — ${row.getString("Role")} / ${row.getString("Rank")}"),
                        Component.literal("XP: $xp / $next; health: ${row.getFloat("Health")} / ${row.getFloat("MaxHealth")}"),
                        Component.literal("Primary ammunition: ${row.getInt("PrimaryAmmo")}; pistol: ${row.getInt("PistolAmmo")}"),
                    ),
                    mouseX,
                    mouseY,
                )
            }
        }
        if (members.size > visible) g.drawString(font, "${scroll + 1}-${minOf(scroll + visible, members.size)} / ${members.size}", left + panelWidth - 65, top + 26, 0xAAAAAA)
    }

    private fun column(index: Int): Int = left + (panelWidth * listOf(0.0, 0.24, 0.43, 0.59, 0.76, 0.87)[index]).toInt()
    private fun line(g: GuiGraphics, text: String, y: Int, color: Int) = g.drawString(font, font.plainSubstrByWidth(text, panelWidth), left, y, color)
    override fun isPauseScreen() = false

    private companion object {
        const val ROW_HEIGHT = 16
    }
}

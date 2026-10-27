package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.client.SquadScreen
import com.sbwnpc.squad.client.WidgetCompat
import com.sbwnpc.squad.client.FactionBanner
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.ConfigureBarracksPayload
import com.sbwnpc.squad.network.ConfigureToolPayload
import com.sbwnpc.squad.network.RequestBarracksQueuePayload
import com.sbwnpc.squad.network.sendToServer
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.npc.TransportVehicle
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Picks what a deployment consists of — preset, class, faction and which vehicle comes with
 * it.
 *
 * Used by two things that describe a deployment the same way: the squad tool, and the Barracks
 * block, which garrisons and reinforces whatever is configured here. They differ only in where the
 * result is sent, which is what [onChange] is for.
 */
class RecruitScreen(
    title: Component,
    private val cfg: SquadToolItem.Config,
    /** Null for the tool, which stores its settings as they are clicked and deploys separately.
     *  Non-null for the Barracks, where nothing is sent until this button is pressed — the label
     *  of the one control that actually deploys. */
    private val deployLabel: String? = null,
    private val barracksPos: BlockPos? = null,
    private var queueSnapshot: CompoundTag = CompoundTag(),
    private val onChange: (SquadToolItem.Config) -> Unit,
) : SquadScreen(title) {

    /** Opened from the squad tool, not a Barracks. */
    val forTool get() = deployLabel == null

    private var preset = cfg.preset
    private var cls = cfg.cls.takeIf { it in NpcClass.selectable } ?: NpcClass.DEFAULT
    private val rank = NpcRank.RECRUIT
    private var faction = cfg.faction
    private var vehicle = cfg.vehicle
    private var vehicleModel = cfg.vehicleModel
    private var tankModel = cfg.tankModel
    private var heliModel = cfg.heliModel

    private lateinit var classBtn: Button
    private lateinit var factionBtn: Button
    private var presetRowY = 0
    private var queueButton: Button? = null
    private var refreshTicks = 0

    override fun init() {
        if (minecraft?.player?.isCreative != true) FactionBanner.faction?.let { faction = it }
        val cx = width / 2
        var y = height / 2 - 62

        // Preset is arrows-only (per user request) — the label between them is plain text, not a
        // button, so clicking it does nothing. Every other row below still cycles on its own click.
        presetRowY = y
        val arrowW = 20
        addRenderableWidget(
            Button.builder(Component.literal("<")) {
                preset = preset.previous()
                classBtn.active = preset == SquadPreset.SINGLE
                changed()
                rebuildWidgets()
            }.bounds(cx - 100, y, arrowW, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal(">")) {
                preset = preset.next()
                classBtn.active = preset == SquadPreset.SINGLE
                changed()
                rebuildWidgets()
            }.bounds(cx + 100 - arrowW, y, arrowW, 20).build(),
        )

        y += 24
        // Vehicle options live between Deploy and Class, and only for the presets they apply to —
        // FIVE/SEVEN can spawn one alongside the squad, T90_CREW picks which tank the crew rides.
        when (preset) {
            SquadPreset.FIVE, SquadPreset.SEVEN -> {
                addRenderableWidget(
                    WidgetCompat.checkbox(font, cx - 100, y, Component.literal("Spawn with vehicle"), vehicle) { value ->
                        vehicle = value
                        changed()
                        rebuildWidgets()
                    },
                )
                y += 24
                if (vehicle) {
                    if (preset == SquadPreset.FIVE) {
                        addRenderableWidget(
                            Button.builder(vehicleModelLabel()) {
                                vehicleModel = vehicleModel.nextTransport()
                                it.message = vehicleModelLabel()
                                changed()
                            }.bounds(cx - 100, y, 200, 20).build(),
                        )
                    } else {
                        // SEVEN has no choice — BMP-2 only. Shown, not clickable, for visibility.
                        addRenderableWidget(
                            Button.builder(Component.literal("Vehicle: ${TransportVehicle.BMP_2.label}").withStyle(ChatFormatting.GOLD)) {}
                                .bounds(cx - 100, y, 200, 20).build(),
                        ).active = false
                    }
                    y += 24
                }
            }

            SquadPreset.T90_CREW -> {
                addRenderableWidget(
                    Button.builder(tankModelLabel()) {
                        tankModel = tankModel.next()
                        it.message = tankModelLabel()
                        changed()
                    }.bounds(cx - 100, y, 200, 20).build(),
                )
                y += 24
            }

            // The airframe also decides who deploys with it — gunship plus gunner, or transport
            // plus the riflemen it carries — so the screen rebuilds to show the new crew.
            SquadPreset.HELI_CREW -> {
                addRenderableWidget(
                    Button.builder(heliModelLabel()) {
                        heliModel = heliModel.next()
                        changed()
                        rebuildWidgets()
                    }.bounds(cx - 100, y, 200, 20).build(),
                )
                y += 24
            }

            else -> Unit
        }

        classBtn = Button.builder(classLabel()) {
            cls = cls.next()
            it.message = classLabel()
            changed()
        }.bounds(cx - 100, y, 200, 20).build()
        classBtn.active = preset == SquadPreset.SINGLE
        // Every other preset fixes its members' classes; a greyed-out class there read as a choice.
        if (preset == SquadPreset.SINGLE) {
            addRenderableWidget(classBtn)
            y += 24
        }
        factionBtn = addRenderableWidget(
            Button.builder(factionLabel()) {
                faction = faction.next()
                it.message = factionLabel()
                changed()
            }.tooltip(Tooltip.create(Component.literal("Survival recruits your faction; creative can choose any faction.")))
                .bounds(cx - 100, y, 200, 20).build(),
        )
        factionBtn.active = minecraft?.player?.isCreative == true

        y += 34
        val label = deployLabel
        if (label == null) {
            addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }.bounds(cx - 100, y, 200, 20).build())
        } else {
            addRenderableWidget(
                Button.builder(Component.literal(label).withStyle(ChatFormatting.GREEN)) {
                    push()
                    onClose()
                }.bounds(cx - 100, y, 126, 20).build(),
            )
            queueButton = addRenderableWidget(
                Button.builder(queueLabel()) {
                    minecraft?.setScreen(BarracksQueueScreen(this, barracksPos!!, queueSnapshot))
                }.bounds(cx + 30, y, 70, 20).build(),
            )
        }
    }

    private fun presetLabel() = Component.literal("Deploy: ${preset.label}").withStyle(ChatFormatting.WHITE)
    private fun classLabel() = Component.literal("Class: ${cls.name}").withStyle(ChatFormatting.GOLD)
    private fun factionLabel() = Component.literal("Faction: ${faction.label}").withStyle(faction.accentStyle)
    private fun vehicleModelLabel() = Component.literal("Vehicle: ${vehicleModel.label}").withStyle(ChatFormatting.GOLD)
    private fun tankModelLabel() = Component.literal("Tank: ${tankModel.label}").withStyle(ChatFormatting.GOLD)

    private fun heliModelLabel() = Component.literal("Helicopter: ${heliModel.label}").withStyle(ChatFormatting.GOLD)

    private fun push() = onChange(SquadToolItem.Config(cls, rank, faction, preset, vehicle, vehicleModel, tankModel, heliModel))

    /**
     * What every settings row calls. The tool stores each change as it is made; the Barracks keeps
     * them local until its deploy button, because for it sending the settings IS spawning the
     * squad — cycling a preset used to deploy one on every arrow press.
     */
    private fun changed() {
        if (deployLabel == null) push()
    }

    private fun queueLabel() = Component.literal("Queue (${queueSnapshot.getList("Entries", Tag.TAG_COMPOUND.toInt()).size})")

    fun updateBarracksQueue(pos: BlockPos, snapshot: CompoundTag) {
        if (pos != barracksPos) return
        queueSnapshot = snapshot
        queueButton?.message = queueLabel()
    }

    override fun tick() {
        super.tick()
        factionBtn.active = minecraft?.player?.isCreative == true
        if (!factionBtn.active) {
            FactionBanner.faction?.let {
                if (faction != it) {
                    faction = it
                    factionBtn.message = factionLabel()
                    changed()
                }
            }
        }
        if (barracksPos != null && ++refreshTicks >= 20) {
            refreshTicks = 0
            sendToServer(RequestBarracksQueuePayload(barracksPos))
        }
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        g.drawCenteredString(font, title, width / 2, height / 2 - 82, 0xFFFFFF)
        g.drawCenteredString(font, presetLabel(), width / 2, presetRowY + 6, 0xFFFFFF)
    }

    override fun isPauseScreen() = false

    companion object {
        private val DEFAULT_CONFIG = SquadToolItem.Config(
            NpcClass.DEFAULT,
            NpcRank.DEFAULT,
            SquadFaction.DEFAULT,
            SquadPreset.DEFAULT,
        )

        /** The squad tool's own config, stored on the held stack. */
        fun forTool(stack: ItemStack) =
            RecruitScreen(
                Component.literal("Deploy Config"),
                SquadToolItem.readConfig(stack) ?: DEFAULT_CONFIG,
            ) { cfg ->
                sendToServer(
                    ConfigureToolPayload(
                        cfg.cls.ordinal,
                        cfg.rank.ordinal,
                        cfg.faction.ordinal,
                        cfg.preset.ordinal,
                        cfg.vehicle,
                        cfg.vehicleModel.ordinal,
                        cfg.tankModel.ordinal,
                        cfg.heliModel.ordinal,
                    ),
                )
            }

        /** Configure future recruitment without replacing living soldiers. */
        fun forBarracks(pos: BlockPos, cfg: SquadToolItem.Config, queue: CompoundTag) =
            RecruitScreen(
                Component.literal("Barracks Garrison"),
                cfg,
                "Deploy",
                pos,
                queue,
            ) { updated ->
                sendToServer(ConfigureBarracksPayload(pos, SquadToolItem.configTag(updated)))
            }
    }
}

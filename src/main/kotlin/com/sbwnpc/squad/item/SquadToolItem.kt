package com.sbwnpc.squad.item

import com.atsuishio.superbwarfare.tools.NBTTool
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.network.OpenCommandScreenPayload
import com.sbwnpc.squad.network.OpenFinishRoutePayload
import com.sbwnpc.squad.network.OpenRecruitScreenPayload
import com.sbwnpc.squad.network.buildSquadSnapshot
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.squad.RouteRecording
import com.sbwnpc.squad.squad.SquadDeployment
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadSelection
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.util.Terrain
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.TooltipContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * One item, two modes (ctrl + right-click air cycles RECRUIT → COMMAND → ...; caught client-side
 * in [com.sbwnpc.squad.client.ToolInputEvents] since ctrl isn't synced player state the way
 * sneaking is, so it can never reach here as `player.isShiftKeyDown` did before).
 *
 * RECRUIT: right-click air → config GUI; right-click block → deploy.
 * COMMAND: right-click air → command GUI (or, if a "set objective" was just armed in the GUI,
 *          raycast where you're looking and set that squad's objective).
 *          right-click NPC → select it / its squad (shift → clear selection).
 *          right-click a hostile mob/player → focus the selected squad on it (hunt/guard by order).
 *
 * Barracks is now a plain placed block (`BarracksBlock`/`BarracksBlockEntity`) with its own
 * BlockItem and its own bare-hand right-click interaction — it doesn't go through this tool at all
 * (per explicit user call: no multitool should be required to place or use it).
 */
class SquadToolItem : Item(Properties().stacksTo(1)) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)

        // Recording a route overrides normal mode dispatch entirely, regardless of which mode the
        // tool happens to be in — same "armed state wins" precedent as ARM_OBJECTIVE/ARM_FOCUS.
        if (!level.isClientSide && player is ServerPlayer && RouteRecording.isRecording(player.uuid)) {
            sendToClient(player, OpenFinishRoutePayload(RouteRecording.pointCount(player.uuid)))
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }

        if (mode(stack) == MODE_RECRUIT) {
            // Round-trips through the server (same pattern as Command mode below) purely so the
            // faction-lock check has somewhere to run before the GUI opens — the client can't
            // know on its own whether this player has picked a faction yet.
            if (!level.isClientSide && player is ServerPlayer) {
                val serverLevel = player.level() as ServerLevel
                if (PlayerFactionRegistry.get(serverLevel).requireOrPrompt(player) != null) {
                    sendToClient(player, OpenRecruitScreenPayload)
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }

        // COMMAND — air click
        if (!level.isClientSide && player is ServerPlayer) {
            val serverLevel = player.level() as ServerLevel
            val armed = SquadSelection.takeObjectiveArm(player.uuid)
            if (armed != null) {
                val pos = Terrain.lookedAtPos(player, serverLevel, 220.0)
                SquadManager.get(serverLevel).setObjective(serverLevel, armed, pos)
                val name = SquadManager.get(serverLevel).get(armed)?.name ?: "Squad"
                actionbar(player, "$name → objective (${pos.x}, ${pos.y}, ${pos.z})", ChatFormatting.GRAY)
            } else {
                val snap = buildSquadSnapshot(SquadManager.get(serverLevel), player.uuid, SquadSelection.looseOf(player.uuid).size)
                sendToClient(player, OpenCommandScreenPayload(snap))
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.SUCCESS
        val stack = context.itemInHand

        if (RouteRecording.isRecording(serverPlayer.uuid)) {
            val count = RouteRecording.addPoint(serverPlayer.uuid, context.clickedPos)
            if (count != null) actionbar(player, "Waypoint $count added", ChatFormatting.GRAY)
            return InteractionResult.CONSUME
        }

        if (mode(stack) == MODE_COMMAND) {
            val squadId = SquadSelection.selectedSquad(player.uuid)
            if (squadId != null) {
                // Close-range: point straight at the ground where you're standing, no GUI round-trip.
                SquadManager.get(serverLevel).setObjective(serverLevel, squadId, context.clickedPos)
                val name = SquadManager.get(serverLevel).get(squadId)?.name ?: "Squad"
                actionbar(player, "$name → objective (${context.clickedPos.x}, ${context.clickedPos.y}, ${context.clickedPos.z})", ChatFormatting.GRAY)
            } else if (player is net.minecraft.server.level.ServerPlayer) {
                val snap = buildSquadSnapshot(SquadManager.get(serverLevel), player.uuid, SquadSelection.looseOf(player.uuid).size)
                sendToClient(player, OpenCommandScreenPayload(snap))
            }
            return InteractionResult.CONSUME
        }

        // RECRUIT: deploy — a single NPC, or a whole preset squad lined up abreast of the click point.
        // Gated on having picked a default once (mandatory first-interaction screen), but the
        // faction actually used to deploy is whatever the tool is currently set to — free choice
        // of ANY faction stays available during development, per explicit user call.
        PlayerFactionRegistry.get(serverLevel).requireOrPrompt(serverPlayer) ?: return InteractionResult.CONSUME
        val cfg = readConfig(stack) ?: Config(NpcClass.DEFAULT, NpcRank.DEFAULT, SquadFaction.DEFAULT, SquadPreset.DEFAULT)
        val pos = context.clickedPos.relative(context.clickedFace)
        val deployed = SquadDeployment.deploy(serverLevel, pos, player.yRot, cfg, player.uuid)
            ?: return InteractionResult.FAIL
        if (deployed.vehicleBlocked) {
            actionbar(player, "No room for the vehicle here — deployed on foot", ChatFormatting.RED)
        } else {
            deployed.squad?.let {
                actionbar(player, "Deployed ${it.name} (${deployed.members.size})", cfg.faction.accentColor)
            }
        }
        return InteractionResult.CONSUME
    }

    override fun interactLivingEntity(stack: ItemStack, player: Player, target: LivingEntity, hand: InteractionHand): InteractionResult {
        if (player.level().isClientSide) return InteractionResult.SUCCESS
        if (mode(stack) != MODE_COMMAND) return InteractionResult.PASS
        val level = player.level() as? ServerLevel ?: return InteractionResult.PASS
        val mgr = SquadManager.get(level)

        if (player.isShiftKeyDown) {
            SquadSelection.clear(player.uuid)
            actionbar(player, "Selection cleared", ChatFormatting.GRAY)
            return InteractionResult.SUCCESS
        }

        // Armed via the GUI's [Focus] button: this click sets the focus, full stop — takes
        // priority over normal NPC selection so you CAN target/guard one of your own NPCs too.
        // (armFocus itself already checked ownership of the commanding squad when it was armed.)
        val armedFocus = SquadSelection.takeFocusArm(player.uuid)
        if (armedFocus != null) {
            mgr.setFocus(armedFocus, target.uuid)
            val squad = mgr.get(armedFocus)
            val verb = if (squad?.order == SquadOrder.DEFEND) "will guard" else "will target"
            actionbar(player, "${squad?.name ?: "Squad"} $verb ${target.name.string}", (squad?.faction?.accentColor ?: ChatFormatting.GRAY))
            return InteractionResult.SUCCESS
        }

        if (target is NpcEntity) {
            val sid = target.squadId
            if (sid != null) {
                // Mid loose-pick (building a new squad from unsquadded NPCs) — clicking one that's
                // already in a squad shouldn't wipe that in-progress selection or silently jump to
                // commanding a different squad instead. Ignored, not an error.
                if (SquadSelection.looseOf(player.uuid).isNotEmpty()) {
                    actionbar(player, "That NPC is already in a squad", ChatFormatting.GRAY)
                    return InteractionResult.SUCCESS
                }
                if (!mgr.ownedBy(sid, player.uuid)) {
                    actionbar(player, "Not your squad", ChatFormatting.RED)
                    return InteractionResult.SUCCESS
                }
                SquadSelection.selectSquad(player.uuid, sid)
                val s = mgr.get(sid)
                actionbar(player, "Selected ${s?.name ?: "squad"} (${s?.members?.size ?: 0})", (s?.faction?.accentColor ?: ChatFormatting.GRAY))
            } else {
                val loose = SquadSelection.looseOf(player.uuid)
                val existingFaction = loose.firstOrNull()?.let { level.getEntity(it) }?.let { SquadTeams.factionOf(it) }
                val targetFaction = SquadTeams.factionOf(target)
                if (loose.isNotEmpty() && existingFaction != targetFaction) {
                    actionbar(player, "Different team — clear selection or form the squad first", ChatFormatting.RED)
                } else {
                    SquadSelection.toggleLoose(player.uuid, target.uuid)
                    actionbar(player, "Selection: ${SquadSelection.looseOf(player.uuid).size}", ChatFormatting.GRAY)
                }
            }
            return InteractionResult.SUCCESS
        }

        // Non-NPC living entity → focus the selected squad on it
        val sid = SquadSelection.selectedSquad(player.uuid) ?: return InteractionResult.PASS
        mgr.setFocus(sid, target.uuid)
        val squad = mgr.get(sid)
        val verb = if (squad?.order == SquadOrder.DEFEND) "will guard" else "will target"
        actionbar(player, "${squad?.name ?: "Squad"} $verb ${target.name.string}", (squad?.faction?.accentColor ?: ChatFormatting.GRAY))
        return InteractionResult.SUCCESS
    }

    private fun actionbar(player: Player, msg: String, color: ChatFormatting) =
        player.displayClientMessage(Component.literal(msg).withStyle(color), true)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val m = when (mode(stack)) {
            MODE_COMMAND -> "COMMAND"
            else -> "RECRUIT"
        }
        tooltip.add(Component.literal("Mode: $m").withStyle(ChatFormatting.YELLOW))
        readConfig(stack)?.let { cfg ->
            val what = if (cfg.preset == SquadPreset.SINGLE) cfg.cls.name else cfg.preset.label
            tooltip.add(Component.literal("Deploy: $what / ${cfg.rank.name}").withStyle(ChatFormatting.GOLD))
            tooltip.add(Component.literal("Faction: ${cfg.faction.label}").withStyle(cfg.faction.accentColor))
        }
        tooltip.add(Component.literal("ctrl+air: switch mode · air: open GUI").withStyle(ChatFormatting.DARK_GRAY))
    }

    data class Config(
        val cls: NpcClass,
        val rank: NpcRank,
        val faction: SquadFaction,
        val preset: SquadPreset,
        val vehicle: Boolean = false,
        val vehicleModel: TransportVehicle = TransportVehicle.DEFAULT,
        val tankModel: TankModel = TankModel.DEFAULT,
        val heliModel: HelicopterModel = HelicopterModel.DEFAULT
    )

    companion object {
        const val KEY_CLASS = "Class"
        const val KEY_RANK = "Rank"
        const val KEY_FACTION = "Faction"
        const val KEY_MODE = "Mode"
        const val KEY_PRESET = "Preset"
        const val KEY_VEHICLE = "Vehicle"
        const val KEY_VEHICLE_MODEL = "VehicleModel"
        const val KEY_TANK_MODEL = "TankModel"
        const val KEY_HELI_MODEL = "HeliModel"
        const val MODE_RECRUIT = 0
        const val MODE_COMMAND = 1

        fun mode(stack: ItemStack) = NBTTool.getTag(stack).getInt(KEY_MODE)

        fun readConfig(stack: ItemStack): Config? {
            if (stack.isEmpty || stack.item !is SquadToolItem) return null
            return readConfig(NBTTool.getTag(stack))
        }

        fun readConfig(tag: CompoundTag): Config = Config(
            readEnum(tag, KEY_CLASS, NpcClass.DEFAULT, { NpcClass.valueOf(it) }, { NpcClass.byOrdinal(it) }),
            readEnum(tag, KEY_RANK, NpcRank.DEFAULT, { NpcRank.valueOf(it) }, { NpcRank.byOrdinal(it) }),
            readEnum(tag, KEY_FACTION, SquadFaction.DEFAULT, { SquadFaction.valueOf(it) }, { SquadFaction.byOrdinal(it) }),
            readEnum(tag, KEY_PRESET, SquadPreset.DEFAULT, { SquadPreset.valueOf(it) }, { SquadPreset.byOrdinal(it) }),
            tag.getBoolean(KEY_VEHICLE),
            readEnum(tag, KEY_VEHICLE_MODEL, TransportVehicle.DEFAULT, { TransportVehicle.valueOf(it) }, { TransportVehicle.byOrdinal(it) }),
            readEnum(tag, KEY_TANK_MODEL, TankModel.DEFAULT, { TankModel.valueOf(it) }, { TankModel.byOrdinal(it) }),
            readEnum(tag, KEY_HELI_MODEL, HelicopterModel.DEFAULT, { HelicopterModel.valueOf(it) }, { HelicopterModel.byOrdinal(it) })
        )

        private inline fun <T> readEnum(tag: CompoundTag, key: String, default: T, byName: (String) -> T, byOrdinal: (Int) -> T): T = when {
            tag.contains(key, Tag.TAG_STRING.toInt()) -> runCatching { byName(tag.getString(key)) }.getOrDefault(default)
            tag.contains(key, Tag.TAG_INT.toInt()) -> byOrdinal(tag.getInt(key))
            else -> default
        }

        /** The same NBT shape [writeConfig] stores on the tool, as a standalone tag — what the
         *  Barracks persists and what its config packets carry. */
        fun configTag(cfg: Config): CompoundTag = CompoundTag().also { writeConfig(it, cfg) }

        fun writeConfig(stack: ItemStack, cfg: Config) {
            NBTTool.withTag(stack) { writeConfig(it, cfg) }
        }

        fun writeConfig(tag: CompoundTag, cfg: Config) {
            tag.putString(KEY_CLASS, cfg.cls.name)
            tag.putString(KEY_RANK, cfg.rank.name)
            tag.putString(KEY_PRESET, cfg.preset.name)
            tag.putString(KEY_FACTION, cfg.faction.name)
            tag.putBoolean(KEY_VEHICLE, cfg.vehicle)
            tag.putString(KEY_VEHICLE_MODEL, cfg.vehicleModel.name)
            tag.putString(KEY_TANK_MODEL, cfg.tankModel.name)
            tag.putString(KEY_HELI_MODEL, cfg.heliModel.name)
        }
    }
}

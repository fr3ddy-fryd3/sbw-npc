package com.sbwnpc.squad.item

import com.atsuishio.superbwarfare.tools.NBTTool
import com.sbwnpc.squad.client.ClientPayloadHandlers
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.network.OpenCommandScreenPayload
import com.sbwnpc.squad.network.buildSquadSnapshot
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadSelection
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobSpawnType
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
 * One item, two modes (shift + right-click air toggles).
 *
 * RECRUIT: right-click air → config GUI; right-click block → deploy.
 * COMMAND: right-click air → command GUI (or, if a "set objective" was just armed in the GUI,
 *          raycast where you're looking and set that squad's objective).
 *          right-click NPC → select it / its squad (shift → clear selection).
 *          right-click a hostile mob/player → focus the selected squad on it (hunt/guard by order).
 */
class SquadToolItem : Item(Properties().stacksTo(1)) {

    private fun mode(stack: ItemStack) = NBTTool.getTag(stack).getInt(KEY_MODE)

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)

        if (player.isShiftKeyDown) {
            if (!level.isClientSide) {
                val next = if (mode(stack) == MODE_COMMAND) MODE_RECRUIT else MODE_COMMAND
                NBTTool.withTag(stack) { it.putInt(KEY_MODE, next) }
                actionbar(player, "Mode: " + if (next == MODE_COMMAND) "COMMAND" else "RECRUIT", ChatFormatting.YELLOW)
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }

        if (mode(stack) == MODE_RECRUIT) {
            if (level.isClientSide) ClientPayloadHandlers.openRecruitScreen(stack)
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }

        // COMMAND — air click
        if (!level.isClientSide && player is ServerPlayer) {
            val serverLevel = player.level() as ServerLevel
            val armed = SquadSelection.takeObjectiveArm(player.uuid)
            if (armed != null) {
                val pos = lookedAtPos(player, serverLevel)
                SquadManager.get(serverLevel).setObjective(armed, pos)
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
        val stack = context.itemInHand

        if (mode(stack) == MODE_COMMAND) return InteractionResult.CONSUME  // command uses air-click, not blocks

        // RECRUIT: deploy
        val cfg = readConfig(stack) ?: Config(NpcClass.DEFAULT, NpcRank.DEFAULT, ChatFormatting.RED)
        val pos = context.clickedPos.relative(context.clickedFace)
        val npc = ModEntities.NPC.get().create(level) ?: return InteractionResult.FAIL
        npc.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, player.yRot + 180f, 0f)
        npc.npcClass = cfg.cls
        npc.npcRank = cfg.rank
        npc.spawnColor = cfg.color
        npc.finalizeSpawn(serverLevel, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWN_EGG, null)
        level.addFreshEntity(npc)
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

        if (target is NpcEntity) {
            val sid = target.squadId
            if (sid != null) {
                SquadSelection.selectSquad(player.uuid, sid)
                val s = mgr.get(sid)
                actionbar(player, "Selected ${s?.name ?: "squad"} (${s?.members?.size ?: 0})", (s?.color ?: ChatFormatting.GRAY))
            } else {
                val loose = SquadSelection.looseOf(player.uuid)
                val existingColor = loose.firstOrNull()?.let { level.getEntity(it) }?.let { SquadTeams.colorOf(it) }
                val targetColor = SquadTeams.colorOf(target)
                if (loose.isNotEmpty() && existingColor != targetColor) {
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
        actionbar(player, "${squad?.name ?: "Squad"} $verb ${target.name.string}", (squad?.color ?: ChatFormatting.GRAY))
        return InteractionResult.SUCCESS
    }

    private fun lookedAtPos(player: ServerPlayer, level: ServerLevel): BlockPos {
        val hit = player.pick(220.0, 1.0f, false)
        if (hit.type == HitResult.Type.BLOCK) return (hit as BlockHitResult).blockPos
        return groundAt(level, hit.location)
    }

    private fun groundAt(level: ServerLevel, loc: Vec3): BlockPos {
        var p = BlockPos.containing(loc)
        var guard = 0
        while (level.getBlockState(p).isAir && p.y > level.minBuildHeight && guard++ < 200) p = p.below()
        return p.above()
    }

    private fun actionbar(player: Player, msg: String, color: ChatFormatting) =
        player.displayClientMessage(Component.literal(msg).withStyle(color), true)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val m = if (mode(stack) == MODE_COMMAND) "COMMAND" else "RECRUIT"
        tooltip.add(Component.literal("Mode: $m").withStyle(ChatFormatting.YELLOW))
        readConfig(stack)?.let { cfg ->
            tooltip.add(Component.literal("Deploy: ${cfg.cls.name} / ${cfg.rank.name}").withStyle(ChatFormatting.GOLD))
            tooltip.add(Component.literal("Colour: ${cfg.color.getName()}").withStyle(cfg.color))
        }
        tooltip.add(Component.literal("shift+air: switch mode · air: open GUI").withStyle(ChatFormatting.DARK_GRAY))
    }

    data class Config(val cls: NpcClass, val rank: NpcRank, val color: ChatFormatting)

    companion object {
        const val KEY_CLASS = "Class"
        const val KEY_RANK = "Rank"
        const val KEY_COLOR = "Color"
        const val KEY_MODE = "Mode"
        const val MODE_RECRUIT = 0
        const val MODE_COMMAND = 1

        private val DEFAULT_COLOR = ChatFormatting.RED

        fun readConfig(stack: ItemStack): Config? {
            if (stack.isEmpty || stack.item !is SquadToolItem) return null
            val tag = NBTTool.getTag(stack)
            return Config(
                if (tag.contains(KEY_CLASS)) NpcClass.byOrdinal(tag.getInt(KEY_CLASS)) else NpcClass.DEFAULT,
                if (tag.contains(KEY_RANK)) NpcRank.byOrdinal(tag.getInt(KEY_RANK)) else NpcRank.DEFAULT,
                if (tag.contains(KEY_COLOR)) SquadTeams.byOrdinal(tag.getInt(KEY_COLOR)) else DEFAULT_COLOR
            )
        }

        fun writeConfig(stack: ItemStack, cls: NpcClass, rank: NpcRank, color: ChatFormatting) {
            NBTTool.withTag(stack) {
                it.putInt(KEY_CLASS, cls.ordinal)
                it.putInt(KEY_RANK, rank.ordinal)
                it.putInt(KEY_COLOR, SquadTeams.ordinalOf(color))
            }
        }
    }
}

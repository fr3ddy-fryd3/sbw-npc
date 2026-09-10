package com.sbwnpc.squad.item

import com.atsuishio.superbwarfare.tools.NBTTool
import com.sbwnpc.squad.client.ClientPayloadHandlers
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.network.OpenCommandScreenPayload
import com.sbwnpc.squad.network.buildSquadSnapshot
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.squad.SquadManager
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
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * One item, Garry's-Mod-multitool style. Two modes toggled by shift + right-click air.
 *
 * RECRUIT: right-click air opens the config GUI (class/rank/colour); right-click a block deploys.
 * COMMAND: right-click an NPC selects it (or its whole squad); right-click air with a squad
 *          selected sets that squad's objective by looking at terrain; otherwise opens the squad
 *          management GUI.
 */
class SquadToolItem : Item(Properties().stacksTo(1)) {

    private fun mode(stack: ItemStack) = NBTTool.getTag(stack).getInt(KEY_MODE)

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)

        if (player.isShiftKeyDown) {
            if (!level.isClientSide) {
                val newMode = if (mode(stack) == MODE_COMMAND) MODE_RECRUIT else MODE_COMMAND
                NBTTool.withTag(stack) { it.putInt(KEY_MODE, newMode) }
                player.displayClientMessage(
                    Component.literal("Mode: ").append(
                        Component.literal(if (newMode == MODE_COMMAND) "COMMAND" else "RECRUIT").withStyle(ChatFormatting.YELLOW)
                    ), true
                )
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }

        if (mode(stack) == MODE_RECRUIT) {
            if (level.isClientSide) ClientPayloadHandlers.openRecruitScreen(stack)
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }

        // COMMAND mode
        if (!level.isClientSide && player is ServerPlayer) {
            commandAirClick(player)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.SUCCESS
        val stack = context.itemInHand

        if (mode(stack) == MODE_COMMAND) {
            val squadId = SquadSelection.selectedSquad(player.uuid)
            if (squadId != null) {
                SquadManager.get(serverLevel).setObjective(squadId, context.clickedPos)
                actionbar(player, "Objective set")
            }
            return InteractionResult.CONSUME
        }

        // RECRUIT: deploy
        val cfg = readConfig(stack) ?: Config(NpcClass.DEFAULT, NpcRank.DEFAULT, SquadTeams.COLORS.first())
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
        if (mode(stack) != MODE_COMMAND || target !is NpcEntity) return InteractionResult.PASS

        val existing = target.squadId
        if (existing != null) {
            SquadSelection.selectSquad(player.uuid, existing)
            val squad = SquadManager.get(player.level() as ServerLevel).get(existing)
            actionbar(player, "Selected ${squad?.name ?: "squad"} (${squad?.members?.size ?: 0})")
        } else {
            SquadSelection.toggleLoose(player.uuid, target.uuid)
            actionbar(player, "Selection: ${SquadSelection.looseOf(player.uuid).size}")
        }
        return InteractionResult.SUCCESS
    }

    private fun commandAirClick(player: ServerPlayer) {
        val level = player.level() as ServerLevel
        val squadId = SquadSelection.selectedSquad(player.uuid)
        if (squadId == null) {
            val snapshot = buildSquadSnapshot(SquadManager.get(level), player.uuid, SquadSelection.looseOf(player.uuid).size)
            sendToClient(player, OpenCommandScreenPayload(snapshot))
            return
        }
        val hit = player.pick(220.0, 1.0f, false)
        val pos: BlockPos = if (hit.type == HitResult.Type.BLOCK) (hit as BlockHitResult).blockPos
        else groundAt(level, hit.location)
        SquadManager.get(level).setObjective(squadId, pos)
        actionbar(player, "Objective set (${pos.x}, ${pos.y}, ${pos.z})")
    }

    private fun groundAt(level: ServerLevel, loc: net.minecraft.world.phys.Vec3): BlockPos {
        var p = BlockPos.containing(loc)
        var guard = 0
        while (level.getBlockState(p).isAir && p.y > level.minBuildHeight && guard++ < 128) p = p.below()
        return p.above()
    }

    private fun actionbar(player: Player, msg: String) =
        player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), true)

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

package com.sbwnpc.squad.item

import com.atsuishio.superbwarfare.tools.NBTTool
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.TooltipContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor

/**
 * Garry's-Mod-multitool-style one item. For now only the "recruit" side exists:
 * - right-click a block → spawn an NPC there with the current config
 * - right-click air → cycle the class
 * - shift + right-click air → cycle the rank
 *
 * Config lives in the stack's CUSTOM_DATA (via SBW's NBTTool). Command mode (squad forming,
 * orders) and a proper config GUI land in Phase 3.
 */
class SquadToolItem : Item(Properties().stacksTo(1)) {

    private fun config(stack: ItemStack): Pair<NpcClass, NpcRank> {
        val tag = NBTTool.getTag(stack)
        return NpcClass.byOrdinal(tag.getInt(KEY_CLASS)) to NpcRank.byOrdinal(tag.getInt(KEY_RANK))
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player ?: return InteractionResult.PASS
        if (level !is ServerLevelAccessor) return InteractionResult.SUCCESS

        val (cls, rank) = config(context.itemInHand)
        val pos = context.clickedPos.relative(context.clickedFace)

        val npc = ModEntities.NPC.get().create(context.level) ?: return InteractionResult.FAIL
        npc.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, player.yRot + 180f, 0f)
        npc.npcClass = cls
        npc.npcRank = rank
        npc.finalizeSpawn(level, context.level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWN_EGG, null)
        context.level.addFreshEntity(npc)

        return InteractionResult.CONSUME
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (!level.isClientSide) {
            val tag = NBTTool.getTag(stack)
            if (player.isShiftKeyDown) {
                val rank = NpcRank.byOrdinal(tag.getInt(KEY_RANK)).next()
                NBTTool.withTag(stack) { it.putInt(KEY_RANK, rank.ordinal) }
                player.displayClientMessage(
                    Component.literal("Rank: ").append(Component.literal(rank.name).withStyle(ChatFormatting.AQUA)),
                    true
                )
            } else {
                val cls = NpcClass.byOrdinal(tag.getInt(KEY_CLASS)).next()
                NBTTool.withTag(stack) { it.putInt(KEY_CLASS, cls.ordinal) }
                player.displayClientMessage(
                    Component.literal("Class: ").append(Component.literal(cls.name).withStyle(ChatFormatting.GOLD)),
                    true
                )
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag
    ) {
        val (cls, rank) = config(stack)
        tooltip.add(Component.literal("Class: ${cls.name}").withStyle(ChatFormatting.GOLD))
        tooltip.add(Component.literal("Rank: ${rank.name}").withStyle(ChatFormatting.AQUA))
        tooltip.add(Component.literal("R-click block: deploy  ·  R-click air: cycle class  ·  Shift: cycle rank")
            .withStyle(ChatFormatting.DARK_GRAY))
    }

    companion object {
        const val KEY_CLASS = "Class"
        const val KEY_RANK = "Rank"
    }
}

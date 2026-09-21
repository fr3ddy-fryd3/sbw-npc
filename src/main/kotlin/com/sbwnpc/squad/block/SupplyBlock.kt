package com.sbwnpc.squad.block

import com.mojang.serialization.MapCodec
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult

/**
 * Placeholder for squad logistics — ammunition, shells, drones and fuel drawn from a point the
 * players stock rather than out of thin air, which is what [BarracksBlock] currently does for
 * reinforcements and what [com.sbwnpc.squad.entity.ai.MortarLoaderBehaviour] currently does for
 * mortar shells.
 *
 * Deliberately inert: it places, breaks and sits there, and says as much when used. It exists now
 * so the block, its id and its recipe slot are settled before anything depends on them — a block
 * added later is a block every existing world is missing.
 */
class SupplyBlock : Block(
    Properties.of().mapColor(MapColor.COLOR_GRAY).strength(4.0f, 8.0f).sound(SoundType.METAL)
) {
    override fun codec(): MapCodec<SupplyBlock> = CODEC

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (!level.isClientSide) {
            player.displayClientMessage(
                Component.literal("Supply point — not wired up yet").withStyle(ChatFormatting.GRAY),
                true
            )
        }
        return InteractionResult.SUCCESS
    }

    companion object {
        val CODEC: MapCodec<SupplyBlock> = simpleCodec { SupplyBlock() }
    }
}

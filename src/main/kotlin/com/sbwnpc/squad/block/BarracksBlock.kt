package com.sbwnpc.squad.block

import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.OpenBarracksScreenPayload
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.squad.BarracksRef
import com.sbwnpc.squad.squad.SquadManager
import com.mojang.serialization.MapCodec
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult

/**
 * Destructible resupply point for a squad — Phase 5.5 scope is purely a periodic respawner of
 * missing members (future resource/ammo logistics build on this same block, per plan, not yet).
 *
 * Was originally a no-AI `Mob` purely to get `hurt()`/health for free — but every OTHER functional
 * placeable SuperbWarfare itself has (ContainerBlock, ChargingStationBlock, VehicleAssemblingTable,
 * etc.) is a plain block: right-click with no tool required, destroyed by mining/explosion like any
 * other block, no bespoke damage tracking. Matching that (explicit user call, after pointing out the
 * Mob version was neither what was asked for nor consistent with SBW's own conventions) means giving
 * up "a rifle can kill it" — it's now only removable by mining or an explosion, same as the rest of
 * SBW's own placeables.
 */
class BarracksBlock : BaseEntityBlock(
    Properties.of().mapColor(MapColor.COLOR_GRAY).strength(6.0f, 12.0f).sound(SoundType.WOOD).noOcclusion()
) {
    override fun codec(): MapCodec<BarracksBlock> = CODEC

    override fun getRenderShape(state: BlockState) = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BarracksBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return createTickerHelper(type, ModBlockEntities.BARRACKS.get(), BarracksBlockEntity::serverTick)
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide || placer == null) return
        val be = level.getBlockEntity(pos) as? BarracksBlockEntity ?: return
        be.owner = placer.uuid
        be.setChanged()
    }

    // Mirrors the old BarracksEntity.die() — any squad still resupplying here needs to be unlinked,
    // not left pointing at a barracks that no longer exists.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!level.isClientSide && !state.`is`(newState.block)) {
            val serverLevel = level as ServerLevel
            val mgr = SquadManager.get(serverLevel)
            mgr.clearBarracks(BarracksRef(serverLevel.dimension(), pos.immutable()))
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    /** Opens the same deployment config the squad tool uses — the Barracks garrisons and reinforces
     *  whatever is set there. No squad selection and no tool required, which is the whole point of
     *  the change: a placed Barracks is usable on its own. */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? BarracksBlockEntity ?: return InteractionResult.PASS
        if (be.owner != player.uuid) {
            actionbar(player, "Not your Barracks", ChatFormatting.RED)
            return InteractionResult.SUCCESS
        }
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.SUCCESS
        sendToClient(serverPlayer, OpenBarracksScreenPayload(pos.immutable(), SquadToolItem.configTag(be.configOrDefault())))
        return InteractionResult.SUCCESS
    }

    private fun actionbar(player: Player, msg: String, color: ChatFormatting) =
        player.displayClientMessage(Component.literal(msg).withStyle(color), true)

    companion object {
        val CODEC: MapCodec<BarracksBlock> = simpleCodec { BarracksBlock() }
    }
}

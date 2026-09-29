package com.sbwnpc.squad.block

import com.mojang.serialization.MapCodec
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.network.OpenSupplyScreenPayload
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult

/**
 * Squad logistics point: NPCs of the placer's side and its allies standing by it are topped back
 * up to what they were issued with — see [SupplyBlockEntity]. Mortar shells still come out of thin
 * air ([com.sbwnpc.squad.entity.ai.MortarLoaderBehaviour]) and vehicles are recharged by
 * SuperbWarfare's own charging station.
 *
 * Blocks placed before it did anything have no block entity and stay inert until put down again.
 */
class SupplyBlock : BaseEntityBlock(
    Properties.of().mapColor(MapColor.COLOR_GRAY).strength(4.0f, 8.0f).sound(SoundType.METAL)
) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun codec(): MapCodec<SupplyBlock> = CODEC

    override fun getRenderShape(state: BlockState) = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = SupplyBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return createTickerHelper(type, ModBlockEntities.SUPPLY.get(), SupplyBlockEntity::serverTick)
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level !is ServerLevel || placer !is Player) return
        val be = level.getBlockEntity(pos) as? SupplyBlockEntity ?: return
        be.faction = PlayerFactionRegistry.get(level).get(placer.uuid)
        be.setChanged()
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? SupplyBlockEntity
        when {
            be == null -> actionbar(player, "Supply point — place it again to use it", ChatFormatting.GRAY)
            !be.serves(PlayerFactionRegistry.get(level).get(player.uuid)) ->
                actionbar(player, "${be.faction?.label ?: "Another side"}'s Supply", ChatFormatting.RED)
            else -> sendToClient(player, OpenSupplyScreenPayload(pos.immutable(), snapshot(player, level, pos, be)))
        }
        return InteractionResult.SUCCESS
    }

    private fun actionbar(player: Player, text: String, color: ChatFormatting) =
        player.displayClientMessage(Component.literal(text).withStyle(color), true)

    companion object {
        val FACING = BlockStateProperties.HORIZONTAL_FACING

        /** What the Supply screen shows [player] at [pos]. */
        fun snapshot(player: ServerPlayer, level: ServerLevel, pos: BlockPos, be: SupplyBlockEntity): CompoundTag {
            val supply = Ports.playerSupply
            val tag = CompoundTag()
            tag.putString("Side", be.faction?.label ?: "Any side")
            val spawn = SupplySpawns.get(level.server).of(player.uuid)
            tag.putBoolean("SpawnHere", spawn != null && spawn.dimension() == level.dimension() && spawn.pos() == pos)
            tag.putInt("Radius", SupplyBlockEntity.RADIUS.toInt())
            val kits = ListTag()
            for (kit in supply.kits) {
                val options = ListTag()
                for (option in kit.options) {
                    val items = ListTag()
                    option.items.forEach { (id, count) ->
                        items.add(CompoundTag().apply {
                            putString("Item", id.toString())
                            putInt("Count", count)
                        })
                    }
                    options.add(CompoundTag().apply {
                        putString("Weapon", option.weapon.toString())
                        put("Items", items)
                    })
                }
                kits.add(CompoundTag().apply {
                    putString("Name", kit.name)
                    put("Options", options)
                })
            }
            tag.put("Kits", kits)
            return tag
        }
        val CODEC: MapCodec<SupplyBlock> = simpleCodec { SupplyBlock() }
    }
}

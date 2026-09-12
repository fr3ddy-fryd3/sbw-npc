package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * Server-tick-driven resupply point (see [BarracksBlock] for why this is a plain block, not the
 * Mob it started out as). `owner` is set once at placement ([BarracksBlock.setPlacedBy]) and never
 * changes; `ticksUntilRespawn` just counts down and resets, no separate scheduler.
 *
 * No `faction` field — an earlier version had one (set from the placing player's own
 * `PlayerFactionRegistry` default) and used it for every squad linked here, which was a real bug:
 * reinforcements should be the SQUAD's own faction ([SquadManager.respawnAtBarracks] now reads
 * `squad.faction` directly), since a squad can be any faction independent of who owns the barracks
 * it resupplies from.
 */
class BarracksBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.BARRACKS.get(), pos, state) {

    var owner: UUID? = null
    private var ticksUntilRespawn = RESPAWN_INTERVAL_TICKS

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        owner?.let { tag.putUUID("Owner", it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        owner = if (tag.hasUUID("Owner")) tag.getUUID("Owner") else null
    }

    companion object {
        private const val RESPAWN_INTERVAL_TICKS = 600 // ~30s

        @JvmStatic
        fun serverTick(level: Level, pos: BlockPos, state: BlockState, be: BarracksBlockEntity) {
            if (level !is ServerLevel) return
            if (--be.ticksUntilRespawn > 0) return
            be.ticksUntilRespawn = RESPAWN_INTERVAL_TICKS
            SquadManager.get(level).respawnAtBarracks(level, pos.immutable())
        }
    }
}

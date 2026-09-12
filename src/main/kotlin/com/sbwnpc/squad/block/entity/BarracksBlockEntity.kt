package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.npc.SquadFaction
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
 * Mob it started out as). `owner`/`faction` are set once at placement ([BarracksBlock.setPlacedBy])
 * and never change; `ticksUntilRespawn` just counts down and resets, no separate scheduler.
 */
class BarracksBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.BARRACKS.get(), pos, state) {

    var owner: UUID? = null
    var faction: SquadFaction? = null
    private var ticksUntilRespawn = RESPAWN_INTERVAL_TICKS

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        owner?.let { tag.putUUID("Owner", it) }
        faction?.let { tag.putString("Faction", it.name) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        owner = if (tag.hasUUID("Owner")) tag.getUUID("Owner") else null
        faction = runCatching { SquadFaction.valueOf(tag.getString("Faction")) }.getOrNull()
    }

    companion object {
        private const val RESPAWN_INTERVAL_TICKS = 600 // ~30s

        @JvmStatic
        fun serverTick(level: Level, pos: BlockPos, state: BlockState, be: BarracksBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            val faction = be.faction ?: return
            if (--be.ticksUntilRespawn > 0) return
            be.ticksUntilRespawn = RESPAWN_INTERVAL_TICKS
            SquadManager.get(serverLevel).respawnAtBarracks(serverLevel, pos.immutable(), faction)
        }
    }
}

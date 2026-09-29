package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.Diplomacy
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * A supply point's stock and its side (see [com.sbwnpc.squad.block.SupplyBlock]).
 *
 * The stock is endless for now: every NPC of [faction] or its allies standing within [RADIUS] is
 * topped back up to what it was issued with ([com.sbwnpc.squad.entity.NpcEntity.resupply]). With no
 * faction — a block nobody placed, say by command — it serves anyone.
 */
class SupplyBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.SUPPLY.get(), pos, state) {

    /** The side of whoever put it down. */
    var faction: SquadFaction? = null

    private var ticksUntilIssue = ISSUE_INTERVAL_TICKS

    fun serves(other: SquadFaction?): Boolean {
        val own = faction ?: return true
        return other != null && Diplomacy.allied(own, other)
    }

    private fun issue(level: ServerLevel) {
        NpcRegistry.forEachWithin(level, blockPos.center, RADIUS) { npc ->
            if (npc.isAlive && serves(SquadTeams.factionOf(npc)) && npc.resupply()) {
                DebugFlags.log("[supply-debug] {} ({}) resupplied at {}", npc.uuid, npc.npcClass, blockPos)
            }
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        faction?.let { tag.putString("Faction", it.name) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        faction = if (tag.contains("Faction")) runCatching { SquadFaction.valueOf(tag.getString("Faction")) }.getOrNull() else null
    }

    companion object {
        const val RADIUS = 8.0
        private const val ISSUE_INTERVAL_TICKS = 20

        fun serverTick(level: Level, pos: BlockPos, state: BlockState, be: SupplyBlockEntity) {
            if (level !is ServerLevel) return
            if (--be.ticksUntilIssue > 0) return
            be.ticksUntilIssue = ISSUE_INTERVAL_TICKS
            be.issue(level)
        }
    }
}

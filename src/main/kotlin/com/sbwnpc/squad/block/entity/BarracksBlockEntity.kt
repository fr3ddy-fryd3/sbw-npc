package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.BarracksRef
import com.sbwnpc.squad.squad.SquadDeployment
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
 * Server-tick-driven garrison point (see [com.sbwnpc.squad.block.BarracksBlock] for why this is a
 * plain block, not the Mob it started out as).
 *
 * Configured exactly the way the squad tool is — same screen, same NBT ([SquadToolItem.Config]) —
 * and it does the same thing with it: deploys that squad once, then keeps it at the strength it
 * was deployed with. This replaced the old "select a squad, then right-click to bind it here"
 * flow, which required the tool, a live squad, and a selection before the block did anything at
 * all.
 *
 * No `faction` field of its own — reinforcements are the SQUAD's faction
 * ([SquadManager.respawnAtBarracks] reads `squad.faction`), which for a garrison is whatever the
 * config here says.
 */
class BarracksBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.BARRACKS.get(), pos, state) {

    var owner: UUID? = null
    /** Null until someone configures it; a configured Barracks with no garrison deploys one. */
    var config: CompoundTag? = null
        private set
    private var garrison: UUID? = null
    private var ticksUntilRespawn = RESPAWN_INTERVAL_TICKS

    fun configOrDefault(): SquadToolItem.Config =
        config?.let { SquadToolItem.readConfig(it) }
            ?: SquadToolItem.Config(NpcClass.DEFAULT, NpcRank.DEFAULT, SquadFaction.DEFAULT, SquadPreset.DEFAULT)

    /**
     * Accepts a new garrison order, replacing whatever is standing here.
     *
     * The previous garrison is deleted outright rather than cut loose: its members were deployed
     * to the old composition, [SquadManager] measures "full strength" against what a squad was
     * formed with, and leaving them behind on every change is how a base ends up with four
     * abandoned squads nobody asked for. This is destructive, which is why the screen makes the
     * player confirm it rather than firing on every click.
     */
    fun configure(level: ServerLevel, cfg: SquadToolItem.Config) {
        clearGarrison(level)
        config = SquadToolItem.configTag(cfg)
        ticksUntilRespawn = 0
        setChanged()
    }

    private fun clearGarrison(level: ServerLevel) {
        val mgr = SquadManager.get(level)
        garrison?.let { mgr.deleteSquad(level, it) }
        // Anything else that was linked here (an older save's manually bound squad) is only
        // unlinked — it was never this Barracks' to delete.
        mgr.clearBarracks(ref(level))
        garrison = null
    }

    private fun ref(level: ServerLevel) = BarracksRef(level.dimension(), blockPos.immutable())

    /** Deploys the configured squad, or tops up the one already deployed. */
    private fun maintainGarrison(level: ServerLevel) {
        val cfg = config?.let { SquadToolItem.readConfig(it) } ?: return
        val owner = owner ?: return
        val mgr = SquadManager.get(level)
        val existing = garrison?.let { mgr.get(it) }
        if (existing != null) {
            mgr.respawnAtBarracks(level, blockPos.immutable())
            return
        }
        // Spawned a block up so a squad doesn't deploy inside the Barracks itself, and facing away
        // from it the way a player's own deploy faces away from them.
        val deployed = SquadDeployment.deploy(level, blockPos.above(), 0f, cfg, owner) ?: return
        val squad = deployed.squad ?: return
        mgr.assignBarracks(squad.id, ref(level))
        garrison = squad.id
        setChanged()
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        owner?.let { tag.putUUID("Owner", it) }
        config?.let { tag.put("Config", it) }
        garrison?.let { tag.putUUID("Garrison", it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        owner = if (tag.hasUUID("Owner")) tag.getUUID("Owner") else null
        config = if (tag.contains("Config")) tag.getCompound("Config") else null
        garrison = if (tag.hasUUID("Garrison")) tag.getUUID("Garrison") else null
    }

    companion object {
        private const val RESPAWN_INTERVAL_TICKS = 600 // ~30s

        @JvmStatic
        fun serverTick(level: Level, pos: BlockPos, state: BlockState, be: BarracksBlockEntity) {
            if (level !is ServerLevel) return
            if (--be.ticksUntilRespawn > 0) return
            be.ticksUntilRespawn = RESPAWN_INTERVAL_TICKS
            be.maintainGarrison(level)
        }
    }
}

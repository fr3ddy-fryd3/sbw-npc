package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.BarracksRecruitmentQueue
import com.sbwnpc.squad.squad.BarracksRef
import com.sbwnpc.squad.squad.SquadDeployment
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/** Initial recruitment and replacements share one saved queue and production clock. */
class BarracksBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.BARRACKS.get(), pos, state) {
    var owner: UUID? = null
    var config: CompoundTag? = null
        private set
    private var garrison: UUID? = null
    private var queue = BarracksRecruitmentQueue()
    private var supportDeployed = false
    private var needsRefresh = true
    private var retryTicks = 0
    private var blockedReason = ""
    private var unresolvedRoster = false
    private var reconfigurePending = false

    fun configOrDefault(ownFaction: SquadFaction): SquadToolItem.Config =
        config?.let { SquadToolItem.readConfig(it) }
            ?: SquadToolItem.Config(NpcClass.DEFAULT, NpcRank.DEFAULT, ownFaction, SquadPreset.DEFAULT)

    /** Reconfiguration changes future places. It never deletes or re-equips a living soldier. */
    fun configure(level: ServerLevel, cfg: SquadToolItem.Config) {
        val mgr = SquadManager.get(level)
        val squad = mgr.get(garrison)
        if (squad != null && squad.faction != cfg.faction) {
            // A different side gets a new squad; the old one stays on its side under manual control.
            mgr.assignBarracks(squad.id, null)
            garrison = null
            supportDeployed = false
        }
        config = SquadToolItem.configTag(cfg)
        reconfigurePending = true
        refresh(level)
        setChanged()
    }

    private fun ref(level: ServerLevel) = BarracksRef(level.dimension(), blockPos.immutable())

    private fun refresh(level: ServerLevel) {
        val mgr = SquadManager.get(level)
        if (garrison != null && mgr.get(garrison) == null) {
            // Disband/delete ends the order, including a garrison waiting for its first NPC.
            config = null
            garrison = null
            reconfigurePending = false
            setChanged()
        }
        val cfg = config?.let { SquadToolItem.readConfig(it) }
        if (cfg != null && garrison == null && owner != null) {
            garrison = mgr.createAtBarracks(level, ref(level), owner!!, cfg).id
            supportDeployed = false
            reconfigurePending = false
            setChanged()
        }
        unresolvedRoster = false
        val wanted = mutableListOf<BarracksRecruitmentQueue.Request>()
        mgr.squadsAtBarracks(ref(level)).forEach { squad ->
            val roster = mgr.ensureRecruitmentRoster(level, squad)
            if (roster == null) {
                unresolvedRoster = true
            } else {
                if (reconfigurePending && squad.id == garrison && cfg != null) {
                    roster.reconfigure(SquadDeployment.composition(cfg))
                    squad.originalComposition = roster.slots.map { it.role }
                    squad.rank = cfg.rank
                    reconfigurePending = false
                    mgr.setDirty()
                    setChanged()
                }
                roster.vacancies().forEach { slot ->
                    wanted += BarracksRecruitmentQueue.Request(squad.id, slot.id, slot.recruited)
                }
            }
        }
        val before = queue.save()
        queue.synchronize(wanted, SquadConfig.recruitIntervalTicks())
        if (queue.save() != before) setChanged()
        if (queue.next() == null) blockedReason = ""
    }

    private fun deploySupport(level: ServerLevel) {
        if (supportDeployed) return
        val cfg = config?.let { SquadToolItem.readConfig(it) } ?: return
        val squad = SquadManager.get(level).get(garrison) ?: return
        val roster = squad.recruitmentRoster ?: return
        if (roster.slots.any { it.active && (!it.recruited || it.occupant == null) }) return
        val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
        if (members.isEmpty() || members.size != squad.members.size) return
        if (SquadDeployment.deploySupport(level, blockPos.above(), 0f, cfg, members)) {
            supportDeployed = true
            setChanged()
        }
    }

    fun recruitmentSnapshot(level: ServerLevel): CompoundTag {
        refresh(level)
        val mgr = SquadManager.get(level)
        return CompoundTag().apply {
            putInt("RemainingTicks", queue.remainingTicks)
            putInt("IntervalTicks", SquadConfig.recruitIntervalTicks())
            putString("Reason", when {
                owner == null -> "No owner assigned"
                blockedReason.isNotEmpty() -> blockedReason
                unresolvedRoster -> "Legacy squads with unknown member roles are waiting for those members to load"
                else -> ""
            })
            put("Entries", ListTag().also { entries ->
                queue.ordered().forEach { request ->
                    val squad = mgr.get(request.squad) ?: return@forEach
                    val slot = squad.recruitmentRoster?.slots?.firstOrNull { it.id == request.slot } ?: return@forEach
                    entries.add(CompoundTag().apply {
                        putString("Squad", squad.name)
                        putString("Role", slot.role.name)
                        putString("Faction", squad.faction.label)
                        putBoolean("Replacement", request.replacement)
                    })
                }
            })
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        owner?.let { tag.putUUID("Owner", it) }
        config?.let { tag.put("Config", it) }
        garrison?.let { tag.putUUID("Garrison", it) }
        tag.put("RecruitmentQueue", queue.save())
        tag.putBoolean("SupportDeployed", supportDeployed)
        tag.putBoolean("ReconfigurePending", reconfigurePending)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        owner = if (tag.hasUUID("Owner")) tag.getUUID("Owner") else null
        config = if (tag.contains("Config")) tag.getCompound("Config") else null
        garrison = if (tag.hasUUID("Garrison")) tag.getUUID("Garrison") else null
        queue = BarracksRecruitmentQueue.load(tag.getCompound("RecruitmentQueue"))
        // Old garrisons already deployed their vehicle. Migration must not create a second one.
        supportDeployed = if (tag.contains("SupportDeployed")) tag.getBoolean("SupportDeployed") else garrison != null
        reconfigurePending = tag.getBoolean("ReconfigurePending")
        needsRefresh = true
    }

    companion object {
        @JvmStatic
        fun serverTick(level: Level, pos: BlockPos, state: BlockState, be: BarracksBlockEntity) {
            if (level !is ServerLevel) return
            if (be.needsRefresh || level.gameTime % 20L == 0L) {
                be.refresh(level)
                be.needsRefresh = false
                SquadManager.get(level).resupplyAtBarracks(level, be.ref(level))
                be.deploySupport(level)
            }
            if (be.owner == null) return
            if (be.queue.remainingTicks > 0) {
                be.queue.tick()
                be.setChanged()
            }
            if (be.retryTicks > 0) { be.retryTicks--; return }
            if (be.queue.remainingTicks > 0) return
            val request = be.queue.next() ?: return
            val reason = SquadManager.get(level).recruitAtBarracks(level, be.ref(level), request)
            be.blockedReason = reason ?: ""
            if (reason == null) {
                be.queue.complete(request, SquadConfig.recruitIntervalTicks())
                be.setChanged()
            } else be.retryTicks = 20
        }
    }
}

package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/** Assessment survives Idle/Fight transitions and still runs while individual NPCs take cover. */
class SquadTacticalBehaviour : ExtendedBehaviour<NpcEntity>() {
    init { noTimeout() }
    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity) = entity.squadId != null
    override fun shouldKeepRunning(entity: NpcEntity) = entity.squadId != null
    override fun tick(entity: NpcEntity) = SquadTactics.refresh(entity)
}

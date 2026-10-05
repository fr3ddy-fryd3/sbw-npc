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
    private fun eligible(entity: NpcEntity) = entity.squadId != null && entity.vehicle == null &&
        entity.npcClass !in setOf(com.sbwnpc.squad.npc.NpcClass.TANK_CREW,com.sbwnpc.squad.npc.NpcClass.HELICOPTER_PILOT,
            com.sbwnpc.squad.npc.NpcClass.HELICOPTER_GUNNER,com.sbwnpc.squad.npc.NpcClass.MORTAR_OPERATOR,com.sbwnpc.squad.npc.NpcClass.MORTAR_LOADER)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity) = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity) = eligible(entity)
    override fun tick(entity: NpcEntity) {
        SquadTactics.refresh(entity)
        com.sbwnpc.squad.combat.tactics.TacticalMovement.observe(entity)
        SquadTactics.equip(entity)
    }
}

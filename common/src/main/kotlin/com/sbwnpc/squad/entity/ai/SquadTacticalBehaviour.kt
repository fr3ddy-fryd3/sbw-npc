package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/** Assessment survives Idle/Fight transitions and still runs while individual NPCs take cover. */
class SquadTacticalBehaviour : ExtendedBehaviour<NpcEntity>() {
    init {
        noTimeout()
    }
    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    // A squad's ground roles start it, so their full agents get made; any man with an NPC agent,
    // an engage-only one too (a crewman or a squadless man in a fight, #251 step B), keeps it so
    // his plan ticks.
    private fun eligible(entity: NpcEntity) =
        entity.vehicle == null && (
            (
                entity.squadId != null &&
                    entity.npcClass !in setOf(
                        com.sbwnpc.squad.npc.NpcClass.TANK_CREW,
                        com.sbwnpc.squad.npc.NpcClass.HELICOPTER_PILOT,
                        com.sbwnpc.squad.npc.NpcClass.HELICOPTER_GUNNER,
                        com.sbwnpc.squad.npc.NpcClass.MORTAR,
                    )
                ) ||
                com.sbwnpc.squad.ai.integration.NativeTactics.hasAgent(entity)
            )
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity) = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity) = eligible(entity)
    override fun stop(entity: NpcEntity) {
        entity.release(NpcActivity.TACTIC, this)
    }

    override fun tick(entity: NpcEntity) {
        // move() runs the planner tick before it answers.
        if (SquadTactics.move(entity)) entity.claim(NpcActivity.TACTIC, this) else entity.release(NpcActivity.TACTIC, this)
        SquadTactics.equip(entity)
    }
}

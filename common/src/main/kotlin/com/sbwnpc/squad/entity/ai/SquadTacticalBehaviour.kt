package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.ai.integration.NativeTactics
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

    // A man his squad plans for starts it, so his full agent gets made, and so does a mortarman,
    // whose crew agent gets made (#258); any man with an NPC agent,
    // an engage-only one too (a crewman or a squadless man in a fight, #251 step B), keeps it so
    // his plan ticks. A helicopter's pilot and gunner run their aircraft's plan from the seat
    // (#260, #261), every tick, as it flies the aircraft. A drone operator's plan flies his drone
    // (#259), a squadless one's too.
    private fun eligible(entity: NpcEntity) =
        if (entity.vehicle != null) {
            NativeTactics.aircrew(entity)
        } else {
            NativeTactics.planned(entity) || NativeTactics.crewed(entity) || NativeTactics.operatesDrone(entity) || NativeTactics.hasAgent(entity)
        }
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity) = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity) = eligible(entity)
    override fun stop(entity: NpcEntity) {
        entity.release(NpcActivity.TACTIC, this)
    }

    override fun tick(entity: NpcEntity) {
        if (entity.vehicle != null) {
            NativeTactics.tick(entity)
            NativeTactics.keepAloft(entity)
            return
        }
        // move() runs the planner tick before it answers.
        if (SquadTactics.move(entity)) entity.claim(NpcActivity.TACTIC, this) else entity.release(NpcActivity.TACTIC, this)
        SquadTactics.equip(entity)
        NativeTactics.keepFlying(entity)
    }
}

package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * SmartBrain migration step 7 — direct port of the old `InvestigateGoal` onto `ExtendedBehaviour`,
 * placed in `NpcEntity.getIdleTasks()` (see there): response to an [com.sbwnpc.squad.combat.Alarm]
 * (heard nearby gunfire, or an ally went down without a resolvable killer) — NOT combat, just "go
 * look". If a real target turns up along the way, `ATTACK_TARGET` becomes non-null and the Fight
 * activity outranks Idle automatically, so this stops on its own.
 *
 * Reads [ModMemories.ALERT_POSITION] (see that memory's own doc comment) instead of
 * `NpcEntity.alertUntilTick`/`alertPos` — expires on its own via TTL, `NpcEntity.clearAlert()`
 * clears it early on arrival, same as before.
 *
 * Placed in Idle rather than a dedicated `Activity`, alongside [SquadOrderBehaviour] (migration step
 * 8) — `!entity.combatLockedByCover()` is still checked explicitly here (Idle behaviours aren't
 * mutually exclusive with `SeekCoverBehaviour`, which lives in Core — see that class's own doc
 * comment for why), same manual bridge the old goal already used.
 */
class InvestigateBehaviour : ExtendedBehaviour<NpcEntity>() {

    companion object {
        private const val ARRIVE_DISTANCE = 3.0

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(ModMemories.ALERT_POSITION.get(), MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    private fun eligible(entity: NpcEntity) = entity.target == null && !entity.combatLockedByCover()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity) && entity.isAlert()

    override fun start(entity: NpcEntity) {
        BrainUtils.getMemory(entity, ModMemories.ALERT_POSITION.get())?.let {
            entity.navigation.moveTo(it.x, it.y, it.z, 1.0)
        }
    }

    override fun tick(entity: NpcEntity) {
        val pos = BrainUtils.getMemory(entity, ModMemories.ALERT_POSITION.get()) ?: return
        if (entity.position().closerThan(pos, ARRIVE_DISTANCE) || entity.navigation.isDone) {
            entity.clearAlert()
        }
    }
}

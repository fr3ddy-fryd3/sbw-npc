package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * SmartBrain migration (finishing the plan's "full migration, not partial" decision) — direct port
 * of the old `GrenadeThrowGoal` onto `ExtendedBehaviour`, placed in `NpcEntity.getFightTasks()`
 * alongside `GunAttackBehaviour`/`AnimatableMeleeAttack` (multiple behaviours in one Activity group
 * just tick concurrently, same as this and the gun goal already did as two unflagged vanilla Goals).
 *
 * Supplements the grenadier's M79 (handled by GunAttackBehaviour) with an occasional thrown grenade
 * at medium range — mainly useful for flushing a target out of cover the launcher can't reach.
 * "Instant action" behaviour: throws once in start(), then immediately deactivates
 * (shouldKeepRunning always false) — the cooldown lives in nextThrowTick rather than needing
 * per-tick ticking while inactive, same idiom the old goal used with canContinueToUse() = false.
 *
 * Throw speed/gravity are placeholder constants (SBW's default projectile gravity 0.05, a guessed
 * ~1.0 blocks/tick toss speed) — need visual tuning once seen in-game.
 */
class GrenadeThrowBehaviour : ExtendedBehaviour<NpcEntity>() {

    private var nextThrowTick = 0

    companion object {
        private const val MIN_RANGE = 5.0
        private const val MAX_RANGE = 16.0
        private const val COOLDOWN_TICKS = 100
        private const val COOLDOWN_JITTER = 60

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean {
        if (entity.npcClass != NpcClass.GRENADIER) return false
        if (entity.vehicleTransport || entity.operatingDrone || entity.servingMortar || entity.antiDroneEngaged) return false
        if (entity.combatLockedByCover()) return false // SeekCoverBehaviour owns the mob until this lapses
        if (entity.tickCount < nextThrowTick) return false
        val target = entity.target ?: return false
        if (!target.isAlive) return false
        val dist = entity.distanceTo(target)
        if (dist !in MIN_RANGE..MAX_RANGE || !entity.sensing.hasLineOfSight(target)) return false
        return GrenadeThrower.isSafeToThrow(entity, target.boundingBox.center)
    }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean = false

    override fun start(entity: NpcEntity) {
        val target = entity.target ?: return
        val level = entity.level() as? ServerLevel ?: return

        GrenadeThrower.throwAt(entity, level, target.boundingBox.center, target.deltaMovement)

        nextThrowTick = entity.tickCount + COOLDOWN_TICKS + entity.random.nextInt(COOLDOWN_JITTER)
    }
}

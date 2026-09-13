package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.tools.MillisTimer
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.Alarm
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * SmartBrain migration step 5 — direct port of the old `NpcGunAttackGoal` into a Fight-activity
 * Behaviour (see `NpcEntity.getFightTasks()`). Only runs while `MemoryModuleType.ATTACK_TARGET` is
 * set — `BrainActivityGroup.fightTasks()` already gates the whole Fight activity on that memory by
 * default (confirmed via javap on the real dependency), same effective condition as the old goal's
 * `mob.target != null` check, just enforced by the framework instead of by hand.
 *
 * Behaviour logic (aim/friendly-fire/bounding-advance/shoot) is otherwise UNCHANGED from
 * NpcGunAttackGoal — this is a port, not a redesign. See that class's original doc comments (now
 * removed) for the reasoning behind each piece; kept condensed here since the "why" is preserved,
 * only the framework glue changed.
 */
class GunAttackBehaviour : ExtendedBehaviour<NpcEntity>() {
    private var aimTime = 0
    private val shootTimer = MillisTimer()

    private val clearAimTimeWhenLostSight = true
    private val zoom = false

    private var lineIsClear = true
    private var blastClear = true
    private var nextSidestepTick = 0
    private var sidestepAttempts = 0

    private var bounding = true
    private var boundPhaseStarted = false
    private var nextBoundToggleTick = 0

    companion object {
        private const val BASE_SHOOT_DISTANCE = 24.0
        private const val DEFEND_LEASH = 14.0
        private const val DEFEND_LEASH_DROP = 24.0
        private const val SIDESTEP_COOLDOWN = 5
        private const val MAX_SIDESTEP_ATTEMPTS = 3
        private const val SIDESTEP_BATCH_COOLDOWN = 40
        private const val BOUND_MOVE_TICKS = 25
        private const val BOUND_PAUSE_TICKS = 20
        private const val GUNFIRE_HEARING_RADIUS = 30.0

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    private val NpcEntity.maxAimTime get() = npcRank.aimTimeTicks
    private val NpcEntity.semiFireInterval get() = npcRank.semiFireIntervalMs
    private val NpcEntity.spread get() = npcRank.spread * npcClass.accuracyMultiplier
    private val NpcEntity.shootDistance get() = BASE_SHOOT_DISTANCE * npcClass.shootDistanceMultiplier

    private fun currentGunData(entity: NpcEntity): GunData? {
        if (entity.mainHandItem.item !is GunItem) return null
        return GunData.from(entity.mainHandItem)
    }

    private fun canEngage(entity: NpcEntity): Boolean {
        if (entity.combatLockedByCover()) return false
        val target = entity.target ?: return false
        val gunData = currentGunData(entity) ?: return false
        return target.isAlive && (gunData.countBackupAmmo(entity) > 0 || gunData.hasEnoughAmmoToShoot(entity))
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = canEngage(entity)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        if (entity.combatLockedByCover()) return false
        val gunData = currentGunData(entity) ?: return false
        return (canEngage(entity) || !entity.navigation.isDone) &&
                (gunData.countBackupAmmo(entity) > 0 || gunData.hasEnoughAmmoToShoot(entity))
    }

    override fun start(entity: NpcEntity) {
        entity.isAggressive = true
    }

    override fun stop(entity: NpcEntity) {
        entity.isAggressive = false
        entity.stopUsingItem()
        aimTime = 0
        shootTimer.stop()
        lineIsClear = true
        blastClear = true
        nextSidestepTick = 0
        sidestepAttempts = 0
        bounding = true
        boundPhaseStarted = false
        nextBoundToggleTick = 0
    }

    private fun advanceOrHold(entity: NpcEntity, target: LivingEntity) {
        if (entity.distanceToSqr(target) <= entity.shootDistance * entity.shootDistance) {
            entity.navigation.stop()
            bounding = true
            boundPhaseStarted = false
            return
        }
        if (!boundPhaseStarted) {
            boundPhaseStarted = true
            bounding = true
            nextBoundToggleTick = entity.tickCount + BOUND_MOVE_TICKS
            moveTowardFormationSlot(entity, target)
            return
        }
        if (entity.tickCount >= nextBoundToggleTick) {
            bounding = !bounding
            nextBoundToggleTick = entity.tickCount + if (bounding) BOUND_MOVE_TICKS else BOUND_PAUSE_TICKS
            if (bounding) moveTowardFormationSlot(entity, target) else entity.navigation.stop()
        }
    }

    private fun moveTowardFormationSlot(entity: NpcEntity, target: LivingEntity) {
        val targetPos = target.position()
        val slot = SquadFormation.slotTarget(entity, targetPos, targetPos.subtract(entity.position()), false)
        entity.navigation.moveTo(slot.x, slot.y, slot.z, 1.0)
    }

    override fun tick(entity: NpcEntity) {
        val target = entity.target ?: return
        val gunData = currentGunData(entity) ?: return

        val canSeeTarget = entity.sensing.hasLineOfSight(target)
        if (canSeeTarget) {
            // Feeds TeamAwareness for the whole faction — this is the ONLY place that reports a
            // sighting (SquadTargetSensor's own nearestDirectTarget only CONSUMES relayed contacts,
            // it doesn't report). Without this, faction-wide awareness would never receive anything
            // regardless of how the current target was acquired (focus/hurt-by/relay/direct).
            SquadTeams.factionOf(entity)?.let { TeamAwareness.report(it, target.uuid, entity.tickCount.toLong()) }
        }
        aimTime = if (canSeeTarget) {
            minOf(entity.maxAimTime, aimTime + 1)
        } else if (clearAimTimeWhenLostSight) {
            0
        } else {
            aimTime - 1
        }

        entity.lookAt(target, 30f, 30f)

        val defendHome = if (entity.currentSquad()?.order == SquadOrder.DEFEND) entity.homeCenter() else null
        if (defendHome != null) {
            val fromHome = entity.position().distanceTo(defendHome)
            if (fromHome > DEFEND_LEASH_DROP) {
                entity.target = null
                entity.navigation.moveTo(defendHome.x, defendHome.y, defendHome.z, 1.0)
                return
            }
            if (fromHome > DEFEND_LEASH) {
                entity.navigation.stop()
            } else {
                advanceOrHold(entity, target)
            }
        } else {
            advanceOrHold(entity, target)
        }

        lineIsClear = FriendlyFireGuard.hasClearLineOfFire(entity, target.eyePosition, entity.spread)
        val explosionRadius = gunData.get(GunProp.EXPLOSION_RADIUS)
        blastClear = FriendlyFireGuard.hasClearBlastRadius(entity, target.position(), explosionRadius)

        if (!lineIsClear) {
            if (entity.tickCount >= nextSidestepTick) {
                if (sidestepAttempts >= MAX_SIDESTEP_ATTEMPTS) {
                    sidestepAttempts = 0
                    nextSidestepTick = entity.tickCount + SIDESTEP_BATCH_COOLDOWN
                } else {
                    nextSidestepTick = entity.tickCount + SIDESTEP_COOLDOWN
                    sidestepAttempts++
                    FriendlyFireGuard.sidestepAwayFromAllies(entity, target.eyePosition)
                }
            }
        } else {
            sidestepAttempts = 0
        }

        gunData.tick(entity, true)

        if (gunData.shouldStartReloading(entity)) {
            gunData.startReload()
        }
        if (gunData.shouldStartBolt()) {
            gunData.startBolt()
        }

        if (lineIsClear && blastClear && gunData.canShoot(entity) && aimTime >= entity.maxAimTime) {
            val rps = gunData.get(GunProp.RPM).toDouble() / 60.0
            var cooldown = Math.round(1000 / rps)

            val fireMode = gunData.selectedFireModeInfo().mode
            if (fireMode == FireMode.SEMI || (fireMode == FireMode.BURST && gunData.burstAmount.get() == 0)) {
                cooldown += entity.semiFireInterval
            }

            if (!shootTimer.started()) {
                shootTimer.start()
                shootTimer.progress = cooldown + 1
            }

            if (shootTimer.progress >= cooldown) {
                var newProgress = shootTimer.progress
                do {
                    gunData.shoot(entity, entity.spread, zoom, target.uuid)
                    newProgress -= cooldown
                } while (newProgress - cooldown > 0)
                shootTimer.progress = newProgress
                entity.lastShotTick = entity.tickCount
                Alarm.raise(entity, entity.position(), target.position(), GUNFIRE_HEARING_RADIUS)
            }
        } else {
            shootTimer.stop()
        }
    }
}

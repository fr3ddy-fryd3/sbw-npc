package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.tools.MillisTimer
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.entity.ai.goal.Goal

/**
 * Adapted from SuperbWarfare's own `GunShootGoal` (entity/goal/GunShootGoal.java), which drives
 * the mod's "any vanilla mob can wield an SBW gun" system via `MobGunData`. That system is
 * data-driven (JSON, weighted random gun pick, auto-equip on world join) and built for wild/random
 * mob spawns — not what we want here, since squad members' loadouts are meant to be assigned
 * deliberately (management tooling, later phases), not rolled randomly.
 *
 * This goal instead just reads whatever [GunItem] is currently in the NPC's main hand and reuses
 * the same aim/shoot/reload tick logic. Aim time / spread / fire cadence come from the NPC's rank.
 */
class NpcGunAttackGoal(private val mob: NpcEntity) : Goal() {
    private var aimTime = 0
    private val shootTimer = MillisTimer()

    private val clearAimTimeWhenLostSight = true
    private val zoom = false

    // Friendly-fire guard: rechecked periodically rather than every tick (cheap enough, but no
    // need to be this precise every single tick), with a cap on sidestep attempts so a boxed-in
    // shooter just holds fire and waits instead of dancing forever.
    private var lineIsClear = true
    private var nextFriendlyCheckTick = 0
    private var sidestepAttempts = 0

    companion object {
        private const val BASE_SHOOT_DISTANCE = 24.0
        private const val DEFEND_LEASH = 14.0
        private const val DEFEND_LEASH_DROP = 24.0
        private const val FRIENDLY_CHECK_INTERVAL = 5
        private const val MAX_SIDESTEP_ATTEMPTS = 3
    }

    // Driven by rank (recruits are slow and inaccurate, elites fast and precise) and class
    // (snipers reach far and shoot straighter, machine-gunners/grenadiers reach a bit further).
    private val maxAimTime get() = mob.npcRank.aimTimeTicks
    private val semiFireInterval get() = mob.npcRank.semiFireIntervalMs
    private val spread get() = mob.npcRank.spread * mob.npcClass.accuracyMultiplier
    private val shootDistance get() = BASE_SHOOT_DISTANCE * mob.npcClass.shootDistanceMultiplier

    private fun currentGunData(): GunData? {
        if (mob.mainHandItem.item !is GunItem) return null
        return GunData.from(mob.mainHandItem)
    }

    override fun canUse(): Boolean {
        if (mob.isSuppressed()) return false // SeekCoverGoal owns the mob until this lapses
        val target = mob.target ?: return false
        val gunData = currentGunData() ?: return false
        return target.isAlive && (gunData.countBackupAmmo(mob) > 0 || gunData.hasEnoughAmmoToShoot(mob))
    }

    override fun canContinueToUse(): Boolean {
        if (mob.isSuppressed()) return false
        val gunData = currentGunData() ?: return false
        return (canUse() || !mob.navigation.isDone) &&
                (gunData.countBackupAmmo(mob) > 0 || gunData.hasEnoughAmmoToShoot(mob))
    }

    override fun start() {
        mob.isAggressive = true
    }

    override fun stop() {
        mob.isAggressive = false
        mob.stopUsingItem()
        aimTime = 0
        shootTimer.stop()
        lineIsClear = true
        nextFriendlyCheckTick = 0
        sidestepAttempts = 0
    }

    override fun requiresUpdateEveryTick() = true

    override fun tick() {
        val target = mob.target ?: return
        val gunData = currentGunData() ?: return

        val canSeeTarget = mob.sensing.hasLineOfSight(target)
        if (canSeeTarget) {
            // Feeds TeamAwareness for the whole faction (not just this squad) — chiefly so a
            // separate mortar crew, which has no infantry of its own to spot anything, can be
            // cued by whoever actually sees the enemy instead of hitting a blind radius.
            SquadTeams.factionOf(mob)?.let { TeamAwareness.report(it, target.uuid, mob.tickCount.toLong()) }
        }
        aimTime = if (canSeeTarget) {
            minOf(maxAimTime, aimTime + 1)
        } else if (clearAimTimeWhenLostSight) {
            0
        } else {
            aimTime - 1
        }

        mob.lookAt(target, 30f, 30f)

        // DEFEND squads hold ground: don't chase far from home, and disengage entirely past the leash.
        val defendHome = if (mob.currentSquad()?.order == com.sbwnpc.squad.squad.SquadOrder.DEFEND) mob.homeCenter() else null
        if (defendHome != null) {
            val fromHome = mob.position().distanceTo(defendHome)
            if (fromHome > DEFEND_LEASH_DROP) {
                mob.target = null
                mob.navigation.moveTo(defendHome.x, defendHome.y, defendHome.z, 1.0)
                return
            }
            if (fromHome > DEFEND_LEASH) {
                mob.navigation.stop()
            } else if (mob.distanceToSqr(target) > shootDistance * shootDistance) {
                mob.navigation.moveTo(target, 1.0)
            } else {
                mob.navigation.stop()
            }
        } else if (mob.distanceToSqr(target) > shootDistance * shootDistance) {
            mob.navigation.moveTo(target, 1.0)
        } else {
            mob.navigation.stop()
        }

        if (mob.tickCount >= nextFriendlyCheckTick) {
            nextFriendlyCheckTick = mob.tickCount + FRIENDLY_CHECK_INTERVAL
            lineIsClear = FriendlyFireGuard.hasClearLineOfFire(mob, target.eyePosition)
            if (!lineIsClear && sidestepAttempts < MAX_SIDESTEP_ATTEMPTS) {
                sidestepAttempts++
                FriendlyFireGuard.sidestepAwayFromAllies(mob, target.eyePosition)
            } else if (lineIsClear) {
                sidestepAttempts = 0
            }
        }

        gunData.tick(mob, true)

        if (gunData.shouldStartReloading(mob)) {
            gunData.startReload()
        }
        if (gunData.shouldStartBolt()) {
            gunData.startBolt()
        }

        if (lineIsClear && gunData.canShoot(mob) && aimTime >= maxAimTime) {
            val rps = gunData.get(GunProp.RPM).toDouble() / 60.0
            var cooldown = Math.round(1000 / rps)

            val fireMode = gunData.selectedFireModeInfo().mode
            if (fireMode == FireMode.SEMI || (fireMode == FireMode.BURST && gunData.burstAmount.get() == 0)) {
                cooldown += semiFireInterval
            }

            if (!shootTimer.started()) {
                shootTimer.start()
                shootTimer.progress = cooldown + 1
            }

            if (shootTimer.progress >= cooldown) {
                var newProgress = shootTimer.progress
                do {
                    gunData.shoot(mob, spread, zoom, target.uuid)
                    newProgress -= cooldown
                } while (newProgress - cooldown > 0)
                shootTimer.progress = newProgress
            }
        } else {
            shootTimer.stop()
        }
    }
}

package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.tools.MillisTimer
import com.sbwnpc.squad.entity.NpcEntity
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
    private val shootDistance = 24.0
    private val zoom = false

    // Driven by rank (recruits are slow and inaccurate, elites fast and precise).
    private val maxAimTime get() = mob.npcRank.aimTimeTicks
    private val semiFireInterval get() = mob.npcRank.semiFireIntervalMs
    private val spread get() = mob.npcRank.spread

    private fun currentGunData(): GunData? {
        if (mob.mainHandItem.item !is GunItem) return null
        return GunData.from(mob.mainHandItem)
    }

    override fun canUse(): Boolean {
        val target = mob.target ?: return false
        val gunData = currentGunData() ?: return false
        return target.isAlive && (gunData.countBackupAmmo(mob) > 0 || gunData.hasEnoughAmmoToShoot(mob))
    }

    override fun canContinueToUse(): Boolean {
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
    }

    override fun requiresUpdateEveryTick() = true

    override fun tick() {
        val target = mob.target ?: return
        val gunData = currentGunData() ?: return

        val canSeeTarget = mob.sensing.hasLineOfSight(target)
        aimTime = if (canSeeTarget) {
            minOf(maxAimTime, aimTime + 1)
        } else if (clearAimTimeWhenLostSight) {
            0
        } else {
            aimTime - 1
        }

        mob.lookAt(target, 30f, 30f)

        if (mob.distanceToSqr(target) > shootDistance * shootDistance) {
            mob.navigation.moveTo(target, 1.0)
        } else {
            mob.navigation.stop()
        }

        gunData.tick(mob, true)

        if (gunData.shouldStartReloading(mob)) {
            gunData.startReload()
        }
        if (gunData.shouldStartBolt()) {
            gunData.startBolt()
        }

        if (gunData.canShoot(mob) && aimTime >= maxAimTime) {
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

package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.tools.MillisTimer
import com.sbwnpc.squad.combat.Alarm
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.entity.LivingEntity
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

    // Friendly-fire guard: re-evaluated every tick (cheap bounded AABB query, and a stale "clear"
    // flag between checks is itself a friendly-fire window). Sidestep MOVEMENT is still only
    // reissued on its own cooldown — no need to reorder navigation every tick. A batch of quick
    // attempts, THEN a longer cooldown before trying again (not giving up permanently) — an earlier
    // version stopped retrying entirely once MAX_SIDESTEP_ATTEMPTS was hit, which is exactly the
    // "just stands there, doesn't step aside" reported after testing: any ally that stayed in the
    // way past the first ~15 ticks left the shooter frozen for the rest of the engagement.
    private var lineIsClear = true
    private var blastClear = true
    private var nextSidestepTick = 0
    private var sidestepAttempts = 0

    // Bounding advance: beyond shootDistance, move in short rushes with a pause between rather than
    // one continuous sprint straight at the target — real infantry advance in bounds, not a flat-out
    // charge, and standing still to look around between rushes is also what lets aimTime/LOS actually
    // matter instead of the mob just closing distance as fast as possible every time.
    private var bounding = true
    private var boundPhaseStarted = false
    private var nextBoundToggleTick = 0

    companion object {
        private const val BASE_SHOOT_DISTANCE = 24.0
        private const val DEFEND_LEASH = 14.0
        private const val DEFEND_LEASH_DROP = 24.0
        private const val SIDESTEP_COOLDOWN = 5
        private const val MAX_SIDESTEP_ATTEMPTS = 3
        private const val SIDESTEP_BATCH_COOLDOWN = 40 // ~2s pause after a batch fails, then retry
        private const val BOUND_MOVE_TICKS = 25   // ~1.25s rush
        private const val BOUND_PAUSE_TICKS = 20  // ~1s pause between rushes
        private const val GUNFIRE_HEARING_RADIUS = 30.0 // detection range, x1.5 per user request (was 20)
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
        if (mob.combatLockedByCover()) return false // SeekCoverGoal owns the mob until this lapses
        val target = mob.target ?: return false
        val gunData = currentGunData() ?: return false
        return target.isAlive && (gunData.countBackupAmmo(mob) > 0 || gunData.hasEnoughAmmoToShoot(mob))
    }

    override fun canContinueToUse(): Boolean {
        if (mob.combatLockedByCover()) return false
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
        blastClear = true
        nextSidestepTick = 0
        sidestepAttempts = 0
        bounding = true
        boundPhaseStarted = false
        nextBoundToggleTick = 0
    }

    /** Beyond shootDistance: bounding advance instead of a flat sprint (see field doc). Within
     *  shootDistance: hold position — this is the "engage" range where NpcGunAttackGoal actually
     *  stops and fights, distinct from however far out target acquisition happened (that's the
     *  target-selector goals' and TeamAwareness's job, not this one's — see PHASE5_PLAN.md).
     *
     *  Subagent review caught a real bug in the first version: `bounding` started `true` with
     *  `nextBoundToggleTick = 0`, so the very first call (`tickCount >= 0` is always true)
     *  immediately flipped `bounding` to `false` and stopped — every fresh engagement against a
     *  distant target began with a dead ~1s freeze instead of an immediate rush, every single time
     *  the goal restarted. `boundPhaseStarted` fixes that: the first call always issues a rush. */
    private fun advanceOrHold(target: LivingEntity) {
        if (mob.distanceToSqr(target) <= shootDistance * shootDistance) {
            mob.navigation.stop()
            bounding = true
            boundPhaseStarted = false // re-engaging a distant target later starts on a rush again
            return
        }
        if (!boundPhaseStarted) {
            boundPhaseStarted = true
            bounding = true
            nextBoundToggleTick = mob.tickCount + BOUND_MOVE_TICKS
            moveTowardFormationSlot(target)
            return
        }
        if (mob.tickCount >= nextBoundToggleTick) {
            bounding = !bounding
            nextBoundToggleTick = mob.tickCount + if (bounding) BOUND_MOVE_TICKS else BOUND_PAUSE_TICKS
            if (bounding) moveTowardFormationSlot(target) else mob.navigation.stop()
        }
    }

    /** Same "spread by formation slot instead of everyone converging on the identical coordinate"
     *  fix as `SquadOrderGoal` — the target's own live position doubles as the anchor. Without this,
     *  a squad that all spotted the same enemy at once (no explicit ATTACK order, just direct/relayed
     *  detection) beelined the exact same point and produced the same "snake" the formation work was
     *  supposed to have already fixed — reported in-game as still happening specifically on contact. */
    private fun moveTowardFormationSlot(target: LivingEntity) {
        val targetPos = target.position()
        val slot = SquadFormation.slotTarget(mob, targetPos, targetPos.subtract(mob.position()), false)
        mob.navigation.moveTo(slot.x, slot.y, slot.z, 1.0)
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
            } else {
                advanceOrHold(target)
            }
        } else {
            advanceOrHold(target)
        }

        // Re-evaluated every tick: a stale "clear" flag from a few ticks ago is itself a
        // friendly-fire window. hasClearLineOfFire models the actual firing cone (spread-based
        // deviation), not just the idealised aim line — a shot can miss the direct line and still
        // clip an ally standing near, not on, it. Explosive weapons (M79) additionally need a
        // blast-radius check at the TARGET's position, independent of the firing cone — a clean
        // shot can still down an ally standing next to what it hits.
        lineIsClear = FriendlyFireGuard.hasClearLineOfFire(mob, target.eyePosition, spread)
        val explosionRadius = gunData.get(GunProp.EXPLOSION_RADIUS)
        blastClear = FriendlyFireGuard.hasClearBlastRadius(mob, target.position(), explosionRadius)

        if (!lineIsClear) {
            if (mob.tickCount >= nextSidestepTick) {
                if (sidestepAttempts >= MAX_SIDESTEP_ATTEMPTS) {
                    // Batch exhausted — pause instead of dancing every 5 ticks forever, but come
                    // back and try a fresh batch shortly rather than freezing for the rest of the
                    // engagement (the ally blocking the shot may well have moved on by then).
                    sidestepAttempts = 0
                    nextSidestepTick = mob.tickCount + SIDESTEP_BATCH_COOLDOWN
                } else {
                    nextSidestepTick = mob.tickCount + SIDESTEP_COOLDOWN
                    sidestepAttempts++
                    // Repositioning the shooter can clear a blocked firing cone, but does nothing
                    // for a blast-radius block (the explosion still lands on the same target
                    // regardless of where the shooter stands) — only worth trying for line-of-fire.
                    FriendlyFireGuard.sidestepAwayFromAllies(mob, target.eyePosition)
                }
            }
        } else {
            sidestepAttempts = 0
        }

        gunData.tick(mob, true)

        if (gunData.shouldStartReloading(mob)) {
            gunData.startReload()
        }
        if (gunData.shouldStartBolt()) {
            gunData.startBolt()
        }

        if (lineIsClear && blastClear && gunData.canShoot(mob) && aimTime >= maxAimTime) {
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
                // Auditory stimulus: nearby allies who didn't see this themselves still hear it and
                // go investigate (see Alarm/InvestigateGoal) — a real "heard gunfire" reaction,
                // distinct from TeamAwareness's "someone has direct LOS on a specific hostile".
                Alarm.raise(mob, mob.position(), target.position(), GUNFIRE_HEARING_RADIUS)
            }
        } else {
            shootTimer.stop()
        }
    }
}

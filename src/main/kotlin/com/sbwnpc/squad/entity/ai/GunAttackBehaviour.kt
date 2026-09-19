package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.tools.MillisTimer
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.Alarm
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DroneCombat
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.OffscreenFire
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.Vec3
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
 *
 * Partial-cover firing position (added later, per user request): once close enough to stop
 * advancing, [advanceOrHold] no longer just freezes wherever the mob happens to be — see
 * [holdFiringPosition]. Distinct from [SeekCoverBehaviour]'s cover-seeking in both trigger and
 * intent: that one only runs while suppressed and tries to get FULLY hidden from every threat,
 * ducking out to peek and back; this one runs during ordinary engagement (this behaviour is only
 * ever active with a live `ATTACK_TARGET` in the first place) and looks for a spot that keeps a
 * clear shot on the target while concealing as much of the mob's own body as the terrain allows —
 * the mob never stops firing while relocating, and never ducks out of sight entirely.
 */
class GunAttackBehaviour : ExtendedBehaviour<NpcEntity>() {
    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story of what this silently broke there); this
    // behaviour is meant to keep running for as long as ATTACK_TARGET stays set, not get force-
    // stopped and immediately restarted every 3 seconds regardless of combat state.
    init {
        noTimeout()
    }

    private var aimTime = 0
    private val shootTimer = MillisTimer()

    private val clearAimTimeWhenLostSight = true
    private val zoom = false

    private var lineIsClear = true
    private var blastClear = true
    // Spread actually used for the current target — wider than entity.spread when the target is
    // a drone (or riding one), see DroneCombat.spreadForTarget. Cached alongside the friendly-fire
    // check so both use the same value a shot will actually be fired with.
    private var shotSpread = 0.0
    private var nextSidestepTick = 0
    private var sidestepAttempts = 0

    private var bounding = true
    private var boundPhaseStarted = false
    private var nextBoundToggleTick = 0

    // See holdFiringPosition(). firingPos is the spot currently being held (set the first time the
    // mob settles into range, and re-evaluated only every HOLD_MIN_TICKS+jitter after that).
    private var firingPos: Vec3? = null
    private var nextPositionCheckTick = 0

    private var nextFriendlyFireCheckTick = 0
    private var nextAlarmTick = 0

    // See OffscreenFire — re-checked every WITNESS_CHECK_INTERVAL ticks, not per shot.
    private var nextWitnessCheckTick = 0
    private var witnessed = true

    // Sustained-fire pacing for AUTO weapons — see the shoot block in tick().
    private var roundsInBurst = 0
    private var burstLimit = MAX_BURST_ROUNDS
    private var burstPauseUntilTick = 0

    companion object {
        private const val BASE_SHOOT_DISTANCE = 24.0
        private const val DEFEND_LEASH = 14.0
        private const val DEFEND_LEASH_DROP = 24.0
        private const val SIDESTEP_COOLDOWN = 5
        private const val MAX_SIDESTEP_ATTEMPTS = 3
        private const val SIDESTEP_BATCH_COOLDOWN = 40
        private const val BOUND_MOVE_TICKS = 25
        private const val GUNFIRE_HEARING_RADIUS = 30.0

        // Friendly-fire assessment used to be two entity queries EVERY tick for every shooter (line
        // of fire + blast radius). Now one combined pass every few ticks — an ally can't cross a
        // firing lane in under a fifth of a second, and the cached answer is what gates the shot.
        private const val FRIENDLY_FIRE_CHECK_INTERVAL = 4

        // Alarm.raise used to fire on EVERY shot (an MG at 600 RPM = 10 ally scans per second per
        // shooter). The alert it raises lasts 200 ticks and re-raising only extends the timer, so
        // once a second is indistinguishable in effect.
        private const val ALARM_INTERVAL_TICKS = 20
        private const val WITNESS_CHECK_INTERVAL = 20

        // Sustained-fire pacing for AUTO fire mode only (SEMI/BURST are already paced by
        // semiFireInterval / the gun's own burst count): after MAX_BURST_ROUNDS(+jitter)
        // consecutive rounds, hold fire for BURST_PAUSE_TICKS(+jitter). Each round is a live SBW
        // projectile entity with its own physics + network sync, and a big fight was sustaining
        // hundreds of them at once — this is the single largest external cost of many NPCs. Also
        // reads more like real fire discipline than a never-ending trigger pull. Tune or set
        // MAX_BURST_ROUNDS very high to effectively disable.
        private const val MAX_BURST_ROUNDS = 8
        private const val BURST_ROUNDS_JITTER = 6
        private const val BURST_PAUSE_TICKS = 10
        private const val BURST_PAUSE_JITTER = 15

        // Was a flat 20 ticks (~1s) — per user request, a firing pause (whether between advance
        // bounds, or holding a chosen partial-cover spot — see holdFiringPosition) should last long
        // enough to actually aim and get shots off, not just barely stop and go again. ~3-5s, staggered
        // so a squad doesn't all reposition in lockstep.
        private const val HOLD_MIN_TICKS = 60
        private const val HOLD_JITTER_TICKS = 40

        // How far out to look for a better partial-cover firing spot once already in range — a
        // local shuffle, not a retreat search (contrast SeekCoverBehaviour's much larger MIN/MAX_
        // RADIUS, which is for fleeing to real cover far from a threat). Widened from 4.0 to 7.0 per
        // user request; 0 (staying exactly put) is still always in range via bestFiringSpot's own
        // baseline score for the entity's current position, so this only affects how far it's
        // willing to reposition, never whether it's allowed to just hold where it already is.
        private const val POSITION_SEARCH_RADIUS = 7.0
        private const val POSITION_GRID_STEP = 2

        // Priority order for holdFiringPosition's concealment scoring — highest first (a candidate
        // blocked at 1.5 hides more of the mob's body than one only blocked at 0.5). All sit below
        // NpcEntity.eyeHeight (~1.62 for this model's standard proportions), so a high score never
        // conflicts with the separate line-of-fire requirement (see concealmentScore's doc comment).
        private val CONCEALMENT_HEIGHTS = listOf(1.5, 1.0, 0.5)

        // Sky blue — debug visual for holdFiringPosition's chosen spot, see markFiringPosition().
        private val FIRING_POSITION_COLOR = org.joml.Vector3f(0.3f, 0.6f, 1.0f)

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
        if (entity.vehicleTransport || entity.operatingDrone || entity.antiDroneEngaged) return false
        if (entity.combatLockedByCover() || entity.combatLockedByMedic()) return false
        val target = entity.target ?: return false
        val gunData = currentGunData(entity) ?: return false
        return target.isAlive && (gunData.countBackupAmmo(entity) > 0 || gunData.hasEnoughAmmoToShoot(entity))
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = canEngage(entity)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        if (entity.combatLockedByCover() || entity.combatLockedByMedic()) return false
        // AntiDroneBehaviour owns the gun (and the mob's feet) while a hostile drone is inbound.
        if (entity.antiDroneEngaged) return false
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
        firingPos = null
        nextPositionCheckTick = 0
        nextFriendlyFireCheckTick = 0
        nextWitnessCheckTick = 0
        witnessed = true
        roundsInBurst = 0
        burstPauseUntilTick = 0
    }

    private fun advanceOrHold(entity: NpcEntity, target: LivingEntity) {
        // Dug in: fight from the hole, never reposition to advance/bound toward the target — see
        // NpcEntity.diggedIn's own doc comment. Aiming/firing below is completely unaffected.
        if (entity.diggedIn) {
            entity.navigation.stop()
            bounding = true
            boundPhaseStarted = false
            return
        }
        if (entity.distanceToSqr(target) <= entity.shootDistance * entity.shootDistance) {
            holdFiringPosition(entity, target)
            bounding = true
            boundPhaseStarted = false
            return
        }
        if (!boundPhaseStarted) {
            boundPhaseStarted = true
            bounding = true
            // Jittered even for this very first move phase — a whole squad typically spots an
            // enemy the same tick, so without this every member's first pause (and therefore every
            // later one, since the cycle just alternates from there) landed at the same moment too:
            // reported in-game as the squad visibly freezing in sync every ~100 ticks before even
            // trading shots. The later pause-phase jitter alone only desyncs members gradually over
            // several cycles, too slow to fix the very first, most noticeable freeze.
            nextBoundToggleTick = entity.tickCount + BOUND_MOVE_TICKS + entity.random.nextInt(HOLD_JITTER_TICKS)
            moveTowardFormationSlot(entity, target)
            return
        }
        if (entity.tickCount >= nextBoundToggleTick) {
            bounding = !bounding
            nextBoundToggleTick = entity.tickCount +
                if (bounding) BOUND_MOVE_TICKS else HOLD_MIN_TICKS + entity.random.nextInt(HOLD_JITTER_TICKS)
            if (bounding) moveTowardFormationSlot(entity, target) else entity.navigation.stop()
        }
    }

    /** Once close enough to fight without needing to advance further, prefer a nearby spot that
     *  keeps a clear shot on [target] while concealing as much of the mob's own body as the terrain
     *  allows, instead of just freezing wherever it happened to stop — see this class's own doc
     *  comment for how this differs from SeekCoverBehaviour. Re-evaluated only every
     *  [HOLD_MIN_TICKS]+jitter (~3-5s): per user request, the mob should actually hold a chosen spot
     *  and fire for a few seconds — real stationary aiming, not endless micro-shuffling every tick.
     *  Aiming/firing in [tick] runs completely unconditionally regardless of what this picks or
     *  whether the mob is still walking the last few steps toward it. */
    private fun holdFiringPosition(entity: NpcEntity, target: LivingEntity) {
        val pos = firingPos
        if (pos != null && entity.tickCount < nextPositionCheckTick) {
            if (entity.position().closerThan(pos, 1.0)) {
                entity.navigation.stop()
            } else {
                entity.navigateTo(pos, 1.0)
            }
            return
        }
        val level = entity.level() as? ServerLevel ?: return
        // Global raycast budget (TickBudget): a whole squad reaches shoot range on the same tick,
        // and each search is up to ~64 candidates x 4 raycasts. If this tick is already spent,
        // keep holding whatever we have and try again in a tick or two — the searches then spread
        // themselves across ticks instead of all landing on one.
        if (!TickBudget.hasRaycasts(level)) {
            nextPositionCheckTick = entity.tickCount + 1 + entity.random.nextInt(3)
            if (pos == null) entity.navigation.stop()
            return
        }
        nextPositionCheckTick = entity.tickCount + HOLD_MIN_TICKS + entity.random.nextInt(HOLD_JITTER_TICKS)
        val chosen = bestFiringSpot(entity, level, target) ?: entity.position()
        firingPos = chosen
        markFiringPosition(level, chosen)
        if (!entity.position().closerThan(chosen, 1.0)) {
            entity.navigation.moveTo(chosen.x, chosen.y, chosen.z, 1.0)
        } else {
            entity.navigation.stop()
        }
    }

    /** Player-facing visual, per user request — same idiom as SeekCoverBehaviour's own
     *  markCoverChoice. One burst each time [holdFiringPosition] (re-)picks a spot, whether or not
     *  it's actually different from the last one, so it's visible even for a squad member that keeps
     *  re-confirming its own current position as still the best available. Gated on
     *  [DebugFlags.MARKERS_ENABLED] — one place to toggle these off, since the user expects to do
     *  that fairly often (e.g. playing with a friend). */
    private fun markFiringPosition(level: ServerLevel, pos: Vec3) {
        if (!DebugFlags.MARKERS_ENABLED) return
        level.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(FIRING_POSITION_COLOR, 1.5f),
            pos.x, pos.y + 0.5, pos.z, 12, 0.3, 0.3, 0.3, 0.0
        )
    }

    /** Scans a small grid within [POSITION_SEARCH_RADIUS] of [entity]'s current position for the
     *  best-scoring valid firing spot (see [concealmentScore]) — null if nothing beats the mob's own
     *  current position (including if the current position IS the best, the common case once
     *  already settled somewhere decent), so [holdFiringPosition] knows to just stay put. */
    private fun bestFiringSpot(entity: NpcEntity, level: ServerLevel, target: LivingEntity): Vec3? {
        val origin = entity.position()
        val eyeHeight = entity.eyeHeight.toDouble()
        var bestScore = concealmentScore(level, entity, target, origin, eyeHeight) ?: -1.0
        var bestPos: Vec3? = null

        val radius = POSITION_SEARCH_RADIUS.toInt()
        for (dx in -radius..radius step POSITION_GRID_STEP) {
            for (dz in -radius..radius step POSITION_GRID_STEP) {
                val distSq = (dx * dx + dz * dz).toDouble()
                if (distSq > POSITION_SEARCH_RADIUS * POSITION_SEARCH_RADIUS) continue
                val ground = Terrain.standableOrNull(level, origin.x + dx, origin.y, origin.z + dz) ?: continue
                val score = concealmentScore(level, entity, target, ground, eyeHeight) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    bestPos = ground
                }
            }
        }
        return bestPos
    }

    /** Null if [pos] has no clear shot at [target] at all — rejected outright, useless as a firing
     *  spot. Otherwise the concealment score: the highest of [CONCEALMENT_HEIGHTS] at which a
     *  raycast from the target's eye to a point that high above [pos] is BLOCKED (real terrain in
     *  the way), or `0.0` if none are blocked (open ground — still a valid spot, just no concealment
     *  bonus). Every height tested is below [eyeHeight], so a high score can never itself block the
     *  line-of-fire check above — a candidate "blocked at 1.5" still sees/shoots fine from its actual
     *  eye height, just with everything below the neck hidden. */
    private fun concealmentScore(
        level: ServerLevel,
        entity: NpcEntity,
        target: LivingEntity,
        pos: Vec3,
        eyeHeight: Double
    ): Double? {
        val myEye = Vec3(pos.x, pos.y + eyeHeight, pos.z)
        if (Sightline.blocked(level, target.eyePosition, myEye, entity)) return null
        for (height in CONCEALMENT_HEIGHTS) {
            val point = Vec3(pos.x, pos.y + height, pos.z)
            if (Sightline.blocked(level, target.eyePosition, point, entity)) return height
        }
        return 0.0
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
            SquadTeams.factionOf(entity)?.let { TeamAwareness.report(it, target.uuid, entity.level().gameTime) }
        }
        aimTime = if (canSeeTarget) {
            minOf(entity.maxAimTime, aimTime + 1)
        } else if (clearAimTimeWhenLostSight) {
            0
        } else {
            aimTime - 1
        }

        entity.lookAt(target, 30f, 30f)
        // lookAt above only sets xRot/yRot (the actual aim SBW fires along) — it never touches
        // yHeadRot/yBodyRot, the RENDERED head/body. Those are driven separately by lookControl,
        // which nothing here was calling: while advancing, BodyRotationControl's "moving" branch
        // snapped the body to match as a side effect, but a target already in range from the very
        // first tick (no approach needed) left the model frozen facing spawn-in direction while
        // still shooting correctly. Same call AntiDroneBehaviour already makes for the same reason.
        entity.lookControl.setLookAt(target.x, target.eyeY, target.z)

        val squad = entity.currentSquad()
        val defendHome = if (squad?.order == SquadOrder.DEFEND) entity.homeCenter() else null
        if (defendHome != null) {
            val fromHome = entity.position().distanceTo(defendHome)
            if (fromHome > DEFEND_LEASH_DROP) {
                entity.target = null
                entity.navigation.moveTo(defendHome.x, defendHome.y, defendHome.z, 1.0)
                return
            }
            if (fromHome > DEFEND_LEASH) {
                // Pinned near the leash edge (won't chase the target further from home) — still
                // shouldn't just freeze in the open (PM review finding): same partial-cover logic
                // as advanceOrHold's own "already in range" branch, just without the bound-toggle
                // bookkeeping that only matters for that other code path.
                holdFiringPosition(entity, target)
            } else {
                advanceOrHold(entity, target)
            }
        } else {
            advanceOrHold(entity, target)
        }

        if (entity.tickCount >= nextFriendlyFireCheckTick) {
            nextFriendlyFireCheckTick = entity.tickCount + FRIENDLY_FIRE_CHECK_INTERVAL
            val explosionRadius = gunData.get(GunProp.EXPLOSION_RADIUS)
            shotSpread = DroneCombat.spreadForTarget(entity.spread, target)
            val assessment = FriendlyFireGuard.assess(
                entity, target.eyePosition, shotSpread, target.position(), explosionRadius
            )
            lineIsClear = assessment.lineClear
            blastClear = assessment.blastClear
        }

        if (!lineIsClear && !entity.diggedIn) {
            // Dug in: hold fire rather than step out of the hole to clear an ally's line of fire —
            // same reasoning as advanceOrHold's guard above.
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
        } else if (lineIsClear) {
            sidestepAttempts = 0
        }

        gunData.tick(entity, true)

        if (gunData.shouldStartReloading(entity)) {
            gunData.startReload()
        }
        if (gunData.shouldStartBolt()) {
            gunData.startBolt()
        }

        val pausedBetweenBursts = entity.tickCount < burstPauseUntilTick
        if (!pausedBetweenBursts && lineIsClear && blastClear && gunData.canShoot(entity) && aimTime >= entity.maxAimTime) {
            val rps = gunData.get(GunProp.RPM).toDouble() / 60.0
            var cooldown = Math.round(1000 / rps).coerceAtLeast(1)

            val fireMode = gunData.selectedFireModeInfo().mode
            if (fireMode == FireMode.SEMI || (fireMode == FireMode.BURST && gunData.burstAmount.get() == 0)) {
                cooldown += entity.semiFireInterval
            }

            if (!shootTimer.started()) {
                shootTimer.start()
                shootTimer.progress = cooldown + 1
            }

            if (shootTimer.progress >= cooldown) {
                if (entity.tickCount >= nextWitnessCheckTick) {
                    nextWitnessCheckTick = entity.tickCount + WITNESS_CHECK_INTERVAL
                    val level = entity.level() as ServerLevel
                    witnessed = OffscreenFire.hasWitness(level, entity, target)
                }
                val simulate = !witnessed && OffscreenFire.canSimulate(gunData)
                var newProgress = shootTimer.progress
                do {
                    if (simulate) {
                        OffscreenFire.fire(entity.level() as ServerLevel, entity, gunData, target, shotSpread)
                    } else {
                        gunData.shoot(entity, shotSpread, zoom, target.uuid)
                    }
                    newProgress -= cooldown
                    roundsInBurst++
                } while (newProgress - cooldown > 0)
                shootTimer.progress = newProgress
                entity.lastShotTick = entity.tickCount
                if (entity.tickCount >= nextAlarmTick) {
                    nextAlarmTick = entity.tickCount + ALARM_INTERVAL_TICKS
                    Alarm.raise(entity, entity.position(), target.position(), GUNFIRE_HEARING_RADIUS)
                }
                if (fireMode == FireMode.AUTO && roundsInBurst >= burstLimit) {
                    roundsInBurst = 0
                    burstLimit = MAX_BURST_ROUNDS + entity.random.nextInt(BURST_ROUNDS_JITTER)
                    burstPauseUntilTick = entity.tickCount + BURST_PAUSE_TICKS + entity.random.nextInt(BURST_PAUSE_JITTER)
                }
            }
        } else {
            shootTimer.stop()
            if (!pausedBetweenBursts) roundsInBurst = 0
        }
    }
}

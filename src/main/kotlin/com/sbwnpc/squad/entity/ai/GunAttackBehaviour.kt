package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.Alarm
import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.DroneCombat
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.OffscreenFire
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.Withdrawal
import com.sbwnpc.squad.combat.tactics.DefensiveOverwatch
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.combat.tactics.TacticalFirePosts
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.domain.port.HandGun
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.TriggerMode
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.util.MillisTimer
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * Aiming, moving and shooting at the target, in the Fight activity (`NpcEntity.getFightTasks()`),
 * which `BrainActivityGroup.fightTasks()` runs only while `MemoryModuleType.ATTACK_TARGET` is set.
 *
 * Partial-cover firing position: once close enough to stop
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
    private val openingFire = OpeningFire()

    /** Patrol combat stays around the place contact began, including on a long assigned route. */
    private var firingOrigin: Vec3? = null
    private var combatOrderStamp = -1
    private var advancingFromPost = false
    private var holdingAdvanceGoal: Vec3? = null
    private var holdingAdvanceComplete = false

    private var lineIsClear = true

    /** Why [lineIsClear] is false, when it is: a vehicle hull rather than a squadmate. The two ask
     *  for opposite responses — see the block in [tick]. */
    private var hullBlocked = false
    private var blastClear = true

    // Spread actually used for the current target — wider than entity.spread when the target is
    // a drone (or riding one), see DroneCombat.spreadForTarget. Cached alongside the friendly-fire
    // check so both use the same value a shot will actually be fired with.
    private var shotSpread = 0.0
    private var nextSidestepTick = 0
    private var sidestepAttempts = 0

    private var bounding = true

    /** Running for its place in a withdrawal this tick — see [withdraw]. */
    private var fallingBack = false
    private var boundPhaseStarted = false
    private var nextBoundToggleTick = 0

    // See holdFiringPosition(). firingPos is the spot currently being held (set the first time the
    // mob settles into range, and re-evaluated only every HOLD_MIN_TICKS+jitter after that).
    private var firingPos: Vec3? = null
    private var nextPositionCheckTick = 0

    private var nextFriendlyFireCheckTick = 0
    private var nextFallBackTick = 0
    private var nextAlarmTick = 0

    // See OffscreenFire — re-checked every WITNESS_CHECK_INTERVAL ticks, not per shot.
    private var nextWitnessCheckTick = 0
    private var witnessed = true

    // Sustained-fire pacing for AUTO weapons — see the shoot block in tick().
    private var roundsInBurst = 0
    private var burstLimit = MAX_BURST_ROUNDS
    private var burstPauseUntilTick = 0

    companion object {
        private const val LAUNCHER_SPREAD_FACTOR = 0.25
        private const val SIDESTEP_COOLDOWN = 5
        private const val MAX_SIDESTEP_ATTEMPTS = 3
        private const val SIDESTEP_BATCH_COOLDOWN = 40
        private const val BOUND_MOVE_TICKS = 25
        private const val GUNFIRE_HEARING_RADIUS = 30.0

        /** A downed pilot with a sidearm has no business closing in: it backs off to this far,
         *  shooting as it goes. */
        private const val KEEP_AWAY_DISTANCE = 40.0
        private const val FALL_BACK_STEP = 16
        private const val FALL_BACK_VERTICAL = 7
        private const val FALL_BACK_REPATH_TICKS = 30
        private const val FALL_BACK_SPEED = 1.4
        private const val WITHDRAW_SPEED = 1.3

        /** Inside a Supply's reach, so the next issue round catches him. */
        private const val SUPPLY_ARRIVE_DISTANCE = com.sbwnpc.squad.block.entity.SupplyBlockEntity.RADIUS - 3.0

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

        /** Slack around the shooter-to-target line when looking for vehicles on it. */
        private const val VEHICLE_LANE_MARGIN = 2.0

        /** Used only when there is no shot at all from where the mob stands — a vehicle hull is
         *  longer than the ordinary search is wide. */
        private const val STUCK_SEARCH_RADIUS = 14.0
        private const val STUCK_GRID_STEP = 3
        private const val POSITION_GRID_STEP = 2
        private const val ARRIVE_DISTANCE = 1.0

        /** A finished path that stopped this close to the spot counts as having got there. */
        private const val SETTLE_DISTANCE = 2.5

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
    private val NpcEntity.shootDistance get() = npcClass.assaultDistance

    /**
     * Swaps a machine gunner onto its launcher for an armoured target and back off it again.
     *
     * Everything downstream — aim time, line of fire, friendly-fire checks, the burst pacing — is
     * written against whatever is in the main hand, so putting the launcher there is the whole
     * implementation. Running dry is not special-cased either: [AntiArmourKit.loaded] stops
     * returning true and the gunner is swapped back for good.
     */
    private fun chooseWeapon(entity: NpcEntity, target: LivingEntity) {
        if (entity.antiArmourWeapon.isEmpty && !AntiArmourKit.isLauncher(entity.mainHandItem)) return
        val wantLauncher = AntiArmourKit.worthARocket(entity, target) && AntiArmourKit.loaded(entity)
        AntiArmourKit.wield(entity, wantLauncher)
    }

    private fun currentGun(entity: NpcEntity): HandGun? = Ports.guns.inHand(entity)

    private fun canEngage(entity: NpcEntity): Boolean {
        if (entity.busyWithRole()) return false
        if (entity.combatLockedByCover() || entity.combatLockedByMedic()) return false
        val target = entity.target ?: return false
        if (entity.incomingFire.replying(entity.level().gameTime) && !canTrackTarget(entity, target)) return false
        val gun = currentGun(entity) ?: return false
        return target.isAlive && gun.hasAmmo()
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = canEngage(entity)

    /** Still able to fight — or, with ammo left, still on its way somewhere. */
    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        if (canEngage(entity)) return true
        // Cover, a medic's treatment or AntiDroneBehaviour (which owns the gun and the feet while
        // a hostile drone is inbound) take the mob over; nothing to finish walking to then.
        if (entity.combatLockedByCover() || entity.combatLockedByMedic() || entity.antiDroneEngaged) return false
        return !entity.navigation.isDone && currentGun(entity)?.hasAmmo() == true
    }

    override fun start(entity: NpcEntity) {
        entity.isAggressive = true
        firingOrigin = entity.position()
        openingFire.reset()
    }

    override fun stop(entity: NpcEntity) {
        entity.readyToCover = false
        // The fight is over; a gunner left holding a launcher would meet the next rifleman with it.
        AntiArmourKit.wield(entity, launcher = false)
        if (!SquadTactics.hasTask(entity)) FiringSpots.release(entity.uuid)
        entity.blockedSightSince = null
        entity.isAggressive = false
        entity.stopUsingItem()
        aimTime = 0
        openingFire.reset()
        firingOrigin = null
        combatOrderStamp = -1
        advancingFromPost = false
        holdingAdvanceGoal = null
        holdingAdvanceComplete = false
        shootTimer.stop()
        lineIsClear = true
        hullBlocked = false
        blastClear = true
        nextSidestepTick = 0
        sidestepAttempts = 0
        bounding = true
        boundPhaseStarted = false
        nextBoundToggleTick = 0
        firingPos = null
        nextPositionCheckTick = 0
        nextFriendlyFireCheckTick = 0
        nextFallBackTick = 0
        nextWitnessCheckTick = 0
        witnessed = true
        roundsInBurst = 0
        burstPauseUntilTick = 0
    }

    private fun advanceOrHold(entity: NpcEntity, target: LivingEntity) {
        val path = entity.navigation.path
        if (path != null && !path.isDone && !safeCombatPath(entity, target, path)) {
            entity.navigation.stop()
            boundPhaseStarted = false
        }
        // Dug in: fight from the hole, never reposition to advance/bound toward the target — see
        // NpcEntity.diggedIn's own doc comment. Aiming/firing below is completely unaffected.
        if (entity.diggedIn) {
            entity.navigation.stop()
            bounding = true
            boundPhaseStarted = false
            return
        }
        val minimumDistance = keepAwayDistance(entity)
        if (minimumDistance > 0.0 && horizontalDistance(entity.position(), target.position()) < minimumDistance) {
            fallBack(entity, target)
            return
        }
        val stoppingDistance = maxOf(entity.shootDistance, CombatPositionSafety.clearance(entity.npcClass) + CombatPositionSafety.APPROACH_MARGIN)
        if (horizontalDistance(entity.position(), target.position()) <= stoppingDistance) {
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

    private fun horizontalDistance(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return Math.sqrt(dx * dx + dz * dz)
    }

    /** Confirm the block-sized firing post, allowing a slab's height difference. */
    private fun atSpot(entity: NpcEntity, pos: Vec3): Boolean = FiringPostArrival.reached(entity.position(), pos)

    private fun rocketAimPoint(target: LivingEntity): Vec3 = target.vehicle?.boundingBox?.center ?: target.position().add(0.0, target.bbHeight * 0.3, 0.0)

    /**
     * A fighting withdrawal to [point], directed for the squad as a whole by [Withdrawal]: this
     * member either runs its half's bound or holds and covers. Everyone keeps shooting whenever
     * the target is in sight — a runner just doesn't stop to do it.
     */
    private fun withdraw(entity: NpcEntity, point: Vec3) {
        bounding = true
        boundPhaseStarted = false
        firingPos = null
        FiringSpots.release(entity.uuid)
        val squad = entity.currentSquad()
        val to = if (squad == null) point else Withdrawal.positionFor(entity, squad, point)
        if (to == null) {
            entity.navigation.stop()
            return
        }
        fallingBack = true
        entity.navigateTo(to, WITHDRAW_SPEED)
    }

    /** A downed pilot is the one role that actively backs away from ordinary contact. */
    private fun keepAwayDistance(entity: NpcEntity): Double =
        if (entity.npcClass == NpcClass.HELICOPTER_PILOT && entity.vehicle == null) {
            KEEP_AWAY_DISTANCE
        } else {
            0.0
        }

    private fun fallBack(entity: NpcEntity, target: LivingEntity) {
        fallingBack = true
        bounding = true
        boundPhaseStarted = false
        firingPos = null
        FiringSpots.release(entity.uuid)
        if (entity.tickCount < nextFallBackTick && !entity.navigation.isDone) return
        nextFallBackTick = entity.tickCount + FALL_BACK_REPATH_TICKS
        val away = DefaultRandomPos.getPosAway(entity, FALL_BACK_STEP, FALL_BACK_VERTICAL, target.position())
        if (away == null || horizontalDistance(away, target.position()) <= horizontalDistance(entity.position(), target.position()) ||
            !entity.navigation.moveTo(away.x, away.y, away.z, FALL_BACK_SPEED)
        ) {
            // Failed retreat must not leave an old assault path running toward the enemy.
            entity.navigation.stop()
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
        val level = entity.level() as? ServerLevel ?: return
        // A grenade has come down by the spot being held: pick another now rather than walk
        // into it and get chased back out by GrenadeEvadeBehaviour.
        if (firingPos?.let { GrenadeHazard.threatens(level, it) } == true) nextPositionCheckTick = 0
        // A useful firing post is kept in every order. An enemy moving farther away is no reason
        // to abandon a clear shot in favour of slightly better concealment.
        val held = firingPos
        if (held != null && entity.tickCount >= nextPositionCheckTick &&
            atSpot(entity, held) && !GrenadeHazard.threatens(level, held) && TickBudget.hasRaycasts(level) &&
            // With the hulls on the line: a spot a vehicle has since pulled in front of is no longer
            // "still has a shot", and keeping it had defenders standing behind a hull, never firing.
            concealmentScore(
                level,
                entity,
                target,
                held,
                entity.eyeHeight.toDouble(),
                Sightline.vehicleHulls(level, AABB(held, target.position()).inflate(VEHICLE_LANE_MARGIN), entity, target),
            )?.let { it > 0.0 } == true &&
            !FiringSpots.crowded(held, FiringSpots.nearbyWithBodies(level, entity, STUCK_SEARCH_RADIUS)) &&
            FriendlyFireGuard.assess(entity, target.eyePosition, entity.spread, target.position(), 0.0).lineClear
        ) {
            nextPositionCheckTick = entity.tickCount + HOLD_MIN_TICKS + entity.random.nextInt(HOLD_JITTER_TICKS)
        }
        val pos = firingPos
        if (pos != null && entity.tickCount < nextPositionCheckTick) {
            if (atSpot(entity, pos)) {
                entity.navigation.stop()
            } else if (safeCombatPosition(entity, target, pos) && FiringPostArrival.steer(entity, pos, 1.0)) {
                // Finish the actual post after vanilla consumes its last waypoint.
            } else {
                navigateCombat(entity, target, pos)
            }
            return
        }
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
        FiringSpots.claim(entity.uuid, chosen)
        markFiringPosition(level, chosen)
        if (DebugFlags.on(LogGroup.ORDER)) {
            DebugFlags.log(
                LogGroup.ORDER,
                "[tactics] {} event=local_position_selected position={} enemyDistance={} concealed={}",
                entity.uuid,
                chosen,
                horizontalDistance(chosen, target.position()),
                concealmentScore(level, entity, target, chosen, entity.eyeHeight.toDouble(), Sightline.vehicleHulls(level, AABB(chosen, target.position()).inflate(VEHICLE_LANE_MARGIN), entity, target)),
            )
        }
        if (!atSpot(entity, chosen)) {
            navigateCombat(entity, target, chosen)
        } else {
            entity.navigation.stop()
        }
    }

    /** Player-facing visual, per user request — same idiom as SeekCoverBehaviour's own
     *  markCoverChoice. One burst each time [holdFiringPosition] (re-)picks a spot, whether or not
     *  it's actually different from the last one, so it's visible even for a squad member that keeps
     *  re-confirming its own current position as still the best available. Gated on
     *  [DebugFlags.markersEnabled] — one place to toggle these off, since the user expects to do
     *  that fairly often (e.g. playing with a friend). */
    private fun markFiringPosition(level: ServerLevel, pos: Vec3) {
        if (!DebugFlags.markersEnabled) return
        level.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(FIRING_POSITION_COLOR, 1.5f),
            pos.x, pos.y + 0.5, pos.z, 12, 0.3, 0.3, 0.3, 0.0,
        )
    }

    /** Scans a small grid within [POSITION_SEARCH_RADIUS] of [entity]'s current position for the
     *  best-scoring valid firing spot (see [concealmentScore]) — null if nothing beats the mob's own
     *  current position (including if the current position IS the best, the common case once
     *  already settled somewhere decent), so [holdFiringPosition] knows to just stay put. */
    private fun bestFiringSpot(entity: NpcEntity, level: ServerLevel, target: LivingEntity): Vec3? {
        val origin = entity.position()
        val eyeHeight = entity.eyeHeight.toDouble()
        // Fetched once for the whole grid: the candidates below are all tested against the same
        // handful of hulls, and an entity query per candidate would be far more than this search
        // can afford.
        val area = AABB(origin, target.position())
            .inflate(STUCK_SEARCH_RADIUS + VEHICLE_LANE_MARGIN)
        val hulls = Sightline.vehicleHulls(level, area, entity, target)
        // Walked once for the whole search rather than per candidate — see FiringSpots.nearby.
        val taken = FiringSpots.nearbyWithBodies(level, entity, STUCK_SEARCH_RADIUS)
        // Standing in a grenade's blast is never the best spot, however good the view.
        val here = if (GrenadeHazard.threatens(level, origin)) {
            null
        } else {
            concealmentScore(level, entity, target, origin, eyeHeight, hulls)
        }
        // Shoulder to shoulder with a squadmate is not a spot worth keeping: any free spot in
        // reach beats it. Still kept if nothing else will do — that's the null return below.
        val baseline = if (here == null || FiringSpots.crowded(origin, taken)) -1.0 else here

        sweep(level, entity, target, origin, eyeHeight, hulls, taken, POSITION_SEARCH_RADIUS, POSITION_GRID_STEP, baseline)
            ?.let { return it }
        // With no reachable improvement nearby, try the wider grid for a blocked or exposed post.
        // Open ground remains valid when the wider search cannot find reachable shelter.
        if (here == null || here == 0.0) {
            return sweep(
                level, entity, target, origin, eyeHeight, hulls, taken,
                STUCK_SEARCH_RADIUS, STUCK_GRID_STEP, baseline,
            )
        }
        return null
    }

    private data class FiringCandidate(val point: Vec3, val concealment: Double)

    private fun sweep(
        level: ServerLevel,
        entity: NpcEntity,
        target: LivingEntity,
        origin: Vec3,
        eyeHeight: Double,
        hulls: List<AABB>,
        taken: List<Vec3>,
        radius: Double,
        step: Int,
        startingScore: Double,
    ): Vec3? {
        val candidates = ArrayList<FiringCandidate>()
        val r = radius.toInt()
        probes@ for (dx in -r..r step step) {
            for (dz in -r..r step step) {
                if (!TickBudget.hasRaycasts(level)) break@probes
                val distSq = (dx * dx + dz * dz).toDouble()
                if (distSq > radius * radius) continue
                val ground = Terrain.standableOrNull(level, origin.x + dx, origin.y, origin.z + dz) ?: continue
                if (FiringSpots.crowded(ground, taken) || GrenadeHazard.threatens(level, ground)) continue
                val score = concealmentScore(level, entity, target, ground, eyeHeight, hulls) ?: continue
                if (score <= startingScore) continue
                if (!FriendlyFireGuard.assess(entity, target.eyePosition, entity.spread, target.position(), 0.0, ground.add(0.0, eyeHeight, 0.0)).lineClear) continue
                candidates.add(FiringCandidate(ground, score))
            }
        }
        // A good-looking unreachable post must not hide the next reachable sheltered candidate.
        val sorted = candidates.sortedWith(compareByDescending<FiringCandidate> { it.concealment }.thenBy { it.point.distanceToSqr(origin) })
        for ((point, _) in sorted) {
            if (!TacticalBudget.path(level.gameTime)) return null
            val path = TacticalPositions.pathToPosition(entity, point)
            if (TacticalPositions.reaches(path, point) && safeCombatPath(entity, target, path!!)) return point
        }
        return null
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
        eyeHeight: Double,
        vehicleHulls: List<AABB>,
    ): Double? {
        if (!canRelocate(entity, pos) || !safeCombatPosition(entity, target, pos)) return null
        val myEye = Vec3(pos.x, pos.y + eyeHeight, pos.z)
        if (Sightline.blocked(level, target.eyePosition, myEye, entity) || !DetectionSightline.visible(level, myEye, target.eyePosition, entity)) return null
        // Standing behind armour is cover from the enemy, but it is not a firing position.
        if (Sightline.crosses(vehicleHulls, myEye, target.eyePosition, entity.spread)) return null
        // Nor is standing ON one. Vehicles have collision, so a mob that walks into a hull rides up
        // onto it, and a spot inside the box is a spot on the roof of an APC.
        if (vehicleHulls.any { it.contains(Vec3(pos.x, pos.y + 0.5, pos.z)) }) return null
        for (height in CONCEALMENT_HEIGHTS) {
            val point = Vec3(pos.x, pos.y + height, pos.z)
            if (Sightline.blocked(level, target.eyePosition, point, entity)) return height
        }
        return 0.0
    }

    /** Optional firing shifts stay near the defend point or the patrol's original contact post. */
    private fun canRelocate(entity: NpcEntity, point: Vec3): Boolean {
        val task = SquadTactics.task(entity)
        if (task?.frontage?.contains(point) == false) return false
        val recoveryPost = task?.localFirePost
        if (recoveryPost != null && !TacticalFirePosts.withinPost(recoveryPost, point, recovery = true)) return false
        val squad = entity.currentSquad() ?: return true
        if (!CombatPosition.holdsPosition(squad.order)) return true
        val post = firingOrigin ?: entity.position()
        if (!CombatPosition.withinArea(post, point, CombatPosition.MAX_ADVANCE_DISTANCE)) return false
        val home = entity.homeCenter()
        return squad.order != SquadOrder.DEFEND || home == null ||
            CombatPosition.withinArea(home, point, CombatPosition.defendRadius(entity.npcClass, squad.members.size))
    }

    private fun holdOpeningFire(entity: NpcEntity) {
        entity.navigation.stop()
        val here = entity.position()
        if (firingPos == null || horizontalDistance(firingPos!!, here) > ARRIVE_DISTANCE) {
            firingPos = here
            FiringSpots.claim(entity.uuid, here)
            nextPositionCheckTick = entity.tickCount + HOLD_MIN_TICKS
        }
    }

    private fun finishHoldingAdvance(entity: NpcEntity) {
        if (!advancingFromPost) return
        advancingFromPost = false
        entity.navigation.stop()
        firingPos = entity.position()
        FiringSpots.claim(entity.uuid, entity.position())
        nextPositionCheckTick = entity.tickCount + HOLD_MIN_TICKS
    }

    private fun tryHoldingAdvance(entity: NpcEntity, target: LivingEntity): Boolean {
        if (entity.diggedIn || holdingAdvanceComplete) return false
        val order = entity.currentSquad()?.order
        if (!CombatPosition.mayAdvance(order, entity.npcClass, entity.position(), target.position())) return false
        // Pick one endpoint for this approach. Following every movement of a distant target
        // around the 24-block boundary made the post wander and continually replaced its path.
        val spot = holdingAdvanceGoal ?: run {
            val goal = CombatPosition.advancePoint(entity.npcClass, firingOrigin ?: entity.position(), target.position())
                ?: return false
            val level = entity.level() as? ServerLevel ?: return false
            Terrain.standableOrNull(level, goal.x, entity.y + 4.0, goal.z, 12) ?: return false
        }
        if (!canRelocate(entity, spot)) return false
        if (atSpot(entity, spot) ||
            (entity.navigation.isDone && horizontalDistance(entity.position(), spot) <= SETTLE_DISTANCE)
        ) {
            holdingAdvanceComplete = true
            return false
        }
        // If the enemy passed behind us, continuing to the old endpoint is no longer an approach.
        if (spot.distanceToSqr(target.position()) >= entity.distanceToSqr(target)) return false
        holdingAdvanceGoal = spot
        firingPos = spot
        FiringSpots.claim(entity.uuid, spot)
        nextPositionCheckTick = entity.tickCount + HOLD_MIN_TICKS
        advancingFromPost = true
        return navigateCombat(entity, target, spot)
    }

    private fun moveTowardFormationSlot(entity: NpcEntity, target: LivingEntity) {
        val targetPos = target.position()
        // Opened out under fire: the marching interval puts the whole squad in one burst.
        val slot = SquadTactics.frontPost(entity)?.point ?: SquadFormation.slotTarget(
            entity,
            targetPos,
            targetPos.subtract(entity.position()),
            false,
            SquadFormation.COMBAT_SPACING,
            combat = true,
        )
        // Each bound ends after a few seconds and discards its path. A 100-block search to a
        // distant enemy is mostly unused; a short leg keeps the same heading and formation.
        val step = CombatPositionSafety.approach(
            entity.position(),
            CombatPosition.assaultStep(entity.position(), slot),
            targetPos,
            CombatPositionSafety.clearance(entity.npcClass) + CombatPositionSafety.APPROACH_MARGIN,
        )
        val level = entity.level() as? ServerLevel ?: return
        val ground = Terrain.standableOrNull(level, step.x, step.y + 4.0, step.z, 12) ?: return
        navigateCombat(entity, target, ground)
    }

    private fun safeCombatPosition(entity: NpcEntity, target: LivingEntity, point: Vec3): Boolean {
        val view = entity.currentSquad()?.tactics?.snapshot
        return CombatPositionSafety.permits(entity.position(), point, target.position(), CombatPositionSafety.clearance(entity.npcClass)) &&
            (view == null || TacticalPositions.safeDistance(entity, view, point))
    }

    private fun safeCombatPath(entity: NpcEntity, target: LivingEntity, path: net.minecraft.world.level.pathfinder.Path): Boolean {
        val view = entity.currentSquad()?.tactics?.snapshot
        if (view != null && !TacticalPositions.safePath(entity, view, path)) return false
        return CombatPositionSafety.permitsPath(entity.position(), path, listOf(target.position()), CombatPositionSafety.clearance(entity.npcClass))
    }

    /** Local firing shifts use the same enemy clearance as coordinated squad routes. */
    private fun navigateCombat(entity: NpcEntity, target: LivingEntity, point: Vec3): Boolean {
        val current = entity.navigation.path
        if (current != null && !current.isDone && TacticalPositions.reaches(current, point) && safeCombatPath(entity, target, current)) return true
        if (current != null && !current.isDone && !safeCombatPath(entity, target, current)) entity.navigation.stop()
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false || !TacticalBudget.path(entity.level().gameTime)) return false
        val path = TacticalPositions.pathToPosition(entity, point)
        if (!safeCombatPosition(entity, target, point) || !TacticalPositions.reaches(path, point) || !safeCombatPath(entity, target, path!!)) {
            entity.navigation.stop()
            return false
        }
        return entity.navigation.moveTo(path, 1.0)
    }

    override fun tick(entity: NpcEntity) {
        val target = entity.target ?: return
        val replyingToIncoming = entity.incomingFire.replying(entity.level().gameTime)
        if (replyingToIncoming && !canTrackTarget(entity, target)) return

        val squad = entity.currentSquad()
        resetForChangedOrder(entity, squad)
        val holding = CombatPosition.holdsPosition(squad?.order)
        // Weapon selection must happen before reading the active gun.
        chooseWeapon(entity, target)
        val gun = currentGun(entity) ?: return

        val visible = canTrackTarget(entity, target)
        val clearShot = visible && hasClearShot(entity, target)
        if (visible) reportVisibleTarget(entity, target)
        updateAim(entity, target, gun, clearShot)

        val movement = updateCombatMovement(entity, target, squad, holding, gun, clearShot)
        if (movement == CombatMovement.RETURNING_TO_POST) return
        // Movement uses the previous safety assessment; refresh it before the next shot.
        updateFiringLane(entity, target, gun)
        if (!clearShot && movement == CombatMovement.TACTICAL && entity.tickCount % 40 == 0) {
            SquadTactics.repositionForFire(entity)
        }
        clearBlockedFiringLane(entity, target, movement == CombatMovement.TACTICAL)
        gun.operate()
        tickShooting(entity, target, gun, clearShot)
    }

    private fun resetForChangedOrder(entity: NpcEntity, squad: Squad?) {
        if (squad == null || squad.orderStamp == combatOrderStamp) return
        combatOrderStamp = squad.orderStamp
        firingOrigin = entity.position()
        firingPos = null
        nextPositionCheckTick = 0
        openingFire.reset()
        boundPhaseStarted = false
        advancingFromPost = false
        holdingAdvanceGoal = null
        holdingAdvanceComplete = false
    }

    private fun hasClearShot(entity: NpcEntity, target: LivingEntity): Boolean {
        // Detection sees through glass; shooting continues to use collision raycasts.
        return if (entity.distanceToSqr(target) > 128.0 * 128.0) {
            !Sightline.blocked(entity.level() as ServerLevel, entity.eyePosition, target.eyePosition, entity)
        } else {
            entity.sensing.hasLineOfSight(target)
        }
    }

    private fun canTrackTarget(entity: NpcEntity, target: LivingEntity): Boolean = DetectionSightline.canSee(entity, target)

    private fun reportVisibleTarget(entity: NpcEntity, target: LivingEntity) {
        entity.rememberVisible(target)
        SquadTactics.observe(entity, target)
        // Relay sightings to the faction; target selection itself only consumes these reports.
        SquadTeams.factionOf(entity)?.let { TeamAwareness.report(it, target.uuid, target.position(), entity.level().gameTime, "${entity.npcClass} ${entity.uuid.toString().take(8)}") }
        com.sbwnpc.squad.squad.SquadReports.contact(entity, target)
    }

    private fun updateAim(entity: NpcEntity, target: LivingEntity, gun: HandGun, canShootTarget: Boolean) {
        if (canShootTarget) {
            entity.blockedSightSince = null
        } else if (entity.blockedSightSince == null) {
            // Stamped once, on the tick sight was lost — GrenadeUseBehaviour measures from here.
            entity.blockedSightSince = entity.tickCount
        }
        // Losing sight starts the aim over.
        aimTime = if (canShootTarget) minOf(entity.maxAimTime, aimTime + 1) else 0

        entity.lookAt(target, 30f, 30f)
        // Rotation aims the shot; lookControl also turns the rendered head and body.
        entity.lookControl.setLookAt(target.x, target.eyeY, target.z)
        // Arc compensation uses the hull/low body point rather than the target eyes.
        gun.arcPitch(entity.eyePosition, rocketAimPoint(target))?.let { entity.xRot = it }
    }

    private enum class CombatMovement { LOCAL, TACTICAL, RETURNING_TO_POST }

    /** First applicable movement owns navigation for this tick. */
    private fun updateCombatMovement(
        entity: NpcEntity,
        target: LivingEntity,
        squad: Squad?,
        holding: Boolean,
        gun: HandGun,
        clearShot: Boolean,
    ): CombatMovement {
        val defendHome = if (squad?.order == SquadOrder.DEFEND) entity.homeCenter() else null
        val retreatTo = entity.retreatPoint()
        val resupplyAt = if (retreatTo == null) entity.lowAmmoFallback() else null
        val opening = openingFire.hold(
            target.uuid,
            entity.tickCount,
            clearShot && lineIsClear && blastClear,
            gun.triggerMode == TriggerMode.AUTO,
        )
        fallingBack = false

        if (retreatTo != null) {
            if (entity.position().distanceTo(retreatTo) > SquadFormation.ARRIVAL_RADIUS) {
                withdraw(entity, retreatTo)
            } else {
                holdFiringPosition(entity, target)
            }
            return CombatMovement.LOCAL
        }
        if (resupplyAt != null) {
            if (entity.position().distanceTo(resupplyAt) > SUPPLY_ARRIVE_DISTANCE) {
                firingPos = null
                FiringSpots.release(entity.uuid)
                fallingBack = true
                entity.navigateTo(resupplyAt, WITHDRAW_SPEED)
            } else {
                holdFiringPosition(entity, target)
            }
            return CombatMovement.LOCAL
        }
        if (entity.coverPeekPoint != null) {
            bounding = true
            boundPhaseStarted = false
            firingPos = null
            return CombatMovement.LOCAL
        }
        if (SquadTactics.holdsAfterFailure(entity)) {
            holdFiringPosition(entity, target)
            return CombatMovement.LOCAL
        }
        if (SquadTactics.move(entity)) {
            bounding = true
            boundPhaseStarted = false
            firingPos = null
            return CombatMovement.TACTICAL
        }
        if (holding) return holdOrderedPosition(entity, target, squad, defendHome, opening)
        if (opening) {
            holdOpeningFire(entity)
        } else if (!lineIsClear && firingPos != null) {
            // Finish clearing an ally's lane before replacing the sidestep with an advance.
            holdFiringPosition(entity, target)
        } else {
            advanceOrHold(entity, target)
        }
        return CombatMovement.LOCAL
    }

    private fun holdOrderedPosition(entity: NpcEntity, target: LivingEntity, squad: Squad?, defendHome: Vec3?, opening: Boolean): CombatMovement {
        val overwatchRadius = if (entity.npcClass in setOf(NpcClass.SNIPER, NpcClass.MACHINE_GUNNER)) DefensiveOverwatch.RADIUS else 0.0
        val leash = CombatPosition.defendRadius(entity.npcClass, squad?.members?.size ?: 1) + overwatchRadius
        if (defendHome != null && !CombatPosition.withinArea(defendHome, entity.position(), leash)) {
            BrainUtils.setTargetOfEntity(entity, null)
            entity.navigation.moveTo(defendHome.x, defendHome.y, defendHome.z, 1.0)
            return CombatMovement.RETURNING_TO_POST
        }
        if (opening) {
            finishHoldingAdvance(entity)
            holdOpeningFire(entity)
        } else if (!tryHoldingAdvance(entity, target)) {
            finishHoldingAdvance(entity)
            holdFiringPosition(entity, target)
        }
        return CombatMovement.LOCAL
    }

    private fun updateFiringLane(entity: NpcEntity, target: LivingEntity, gun: HandGun) {
        if (entity.tickCount >= nextFriendlyFireCheckTick) {
            nextFriendlyFireCheckTick = entity.tickCount + FRIENDLY_FIRE_CHECK_INTERVAL
            val explosionRadius = gun.explosionRadius
            shotSpread = DroneCombat.spreadForTarget(entity.spread, target)
            val assessment = FriendlyFireGuard.assess(
                entity,
                SquadTactics.firingLaneEnd(entity, target),
                shotSpread,
                target.position(),
                explosionRadius,
            )
            // A vehicle in the way counts as "line not clear" too, but it is answered differently
            // from an ally in the way — see the block further down.
            val level = entity.level() as ServerLevel
            val lane = AABB(entity.eyePosition, target.eyePosition).inflate(VEHICLE_LANE_MARGIN)
            val hulls = Sightline.vehicleHulls(level, lane, entity, target)
            val hullInTheWay =
                Sightline.crosses(hulls, entity.eyePosition, target.eyePosition, shotSpread)
            lineIsClear = assessment.lineClear && !hullInTheWay
            hullBlocked = hullInTheWay
            if (DebugFlags.on(LogGroup.FIRE) && hulls.isNotEmpty()) {
                DebugFlags.log(
                    LogGroup.FIRE,
                    "{} at {} target {} at {} hulls {} vehicles on lane={} crossing={} allyClear={} -> lineIsClear={} answer={}",
                    entity.uuid, entity.blockPosition(), target.name.string, target.blockPosition(),
                    hulls.map { net.minecraft.core.BlockPos.containing(it.center) },
                    hulls.size, hullInTheWay, assessment.lineClear, lineIsClear,
                    if (lineIsClear) {
                        "fire"
                    } else if (hullInTheWay) {
                        "reposition"
                    } else {
                        "sidestep"
                    },
                )
            }
            blastClear = assessment.blastClear
        }
    }

    private fun clearBlockedFiringLane(entity: NpcEntity, target: LivingEntity, tacticalMovement: Boolean) {
        // Do not replace withdrawal, cover peeking or dug-in positions with sidesteps.
        if (!lineIsClear && !entity.diggedIn && !fallingBack && entity.coverPeekPoint == null) {
            // Dug in: hold fire rather than step out of the hole to clear an ally's line of fire —
            // same reasoning as advanceOrHold's guard above.
            if (tacticalMovement) {
                SquadTactics.repositionForFire(entity)
            } else if (hullBlocked) {
                // Choose another firing position; an ally sidestep cannot clear a hull.
                nextPositionCheckTick = 0
            } else if (entity.tickCount >= nextSidestepTick &&
                (firingPos == null || entity.navigation.isDone || atSpot(entity, firingPos!!))
            ) {
                if (sidestepAttempts >= MAX_SIDESTEP_ATTEMPTS) {
                    sidestepAttempts = 0
                    nextSidestepTick = entity.tickCount + SIDESTEP_BATCH_COOLDOWN
                } else {
                    nextSidestepTick = entity.tickCount + SIDESTEP_COOLDOWN
                    sidestepAttempts++
                    FriendlyFireGuard.sidestepAwayFromAllies(entity, target.eyePosition) { canRelocate(entity, it) }?.let {
                        firingPos = it
                        FiringSpots.claim(entity.uuid, it)
                        nextPositionCheckTick = entity.tickCount + HOLD_MIN_TICKS
                    }
                }
            }
        } else if (lineIsClear) {
            sidestepAttempts = 0
        }
    }

    private enum class FireBlocker { SIGHT_BLOCKED, ARMOUR, BURST_PAUSE, FRIENDLY_OR_VEHICLE, BLAST_SAFETY, WEAPON_CYCLE, AIMING }
    private var lastFireBlocker: FireBlocker? = null
    private var lastFireTarget: java.util.UUID? = null

    private fun fireBlocker(entity: NpcEntity, target: LivingEntity, gun: HandGun, clearShot: Boolean, pausedBetweenBursts: Boolean): FireBlocker? =
        when {
            !clearShot -> FireBlocker.SIGHT_BLOCKED
            !SquadTactics.permitsFire(entity, target) -> FireBlocker.ARMOUR
            pausedBetweenBursts -> FireBlocker.BURST_PAUSE
            !lineIsClear -> FireBlocker.FRIENDLY_OR_VEHICLE
            !blastClear -> FireBlocker.BLAST_SAFETY
            !gun.canShoot() -> FireBlocker.WEAPON_CYCLE
            aimTime < entity.maxAimTime -> FireBlocker.AIMING
            else -> null
        }

    private fun traceFireBlocker(entity: NpcEntity, target: LivingEntity, blocker: FireBlocker?) {
        if (!DebugFlags.on(LogGroup.ORDER)) return
        if (lastFireTarget == target.uuid && lastFireBlocker == blocker) return
        lastFireTarget = target.uuid
        lastFireBlocker = blocker
        val squad = entity.currentSquad()
        val plan = squad?.tactics?.plan
        DebugFlags.log(
            LogGroup.ORDER,
            "[tactics] {} squad={} plan={} event=fire_gate job={} target={} reason={} distance={}",
            entity.uuid,
            squad?.id,
            plan?.id,
            plan?.tasks?.get(entity.uuid)?.job,
            target.uuid,
            blocker ?: "READY",
            entity.distanceTo(target),
        )
    }

    private fun tickShooting(entity: NpcEntity, target: LivingEntity, gun: HandGun, canShootTarget: Boolean) {
        val pausedBetweenBursts = entity.tickCount < burstPauseUntilTick
        entity.readyToCover = canShootTarget && lineIsClear && blastClear && gun.hasAmmo() &&
            aimTime >= entity.maxAimTime && SquadTactics.permitsFire(entity, target)
        val blocker = fireBlocker(entity, target, gun, canShootTarget, pausedBetweenBursts)
        traceFireBlocker(entity, target, blocker)
        if (blocker == null) {
            val rps = gun.roundsPerMinute / 60.0
            var cooldown = Math.round(1000 / rps).coerceAtLeast(1)

            val fireMode = gun.triggerMode
            if (gun.needsTriggerReset) {
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
                val simulate = !witnessed && OffscreenFire.canSimulate(gun)
                // A rocket is aimed, not sprayed: the rifleman's own spread (5-7) throws it about 5
                // degrees either way — three blocks over or under a tank at thirty — so launchers
                // fire with a fraction of it. The arc itself (arcPitch) was already on target.
                val spread = if (gun.explosionRadius > 0.0) shotSpread * LAUNCHER_SPREAD_FACTOR else shotSpread
                var newProgress = shootTimer.progress
                do {
                    if (simulate) {
                        OffscreenFire.fire(entity, gun, target, spread)
                    } else {
                        gun.shootAt(spread, zoom = false, target.uuid)
                    }
                    newProgress -= cooldown
                    roundsInBurst++
                    openingFire.fired()
                } while (newProgress - cooldown > 0)
                shootTimer.progress = newProgress
                entity.lastShotTick = entity.tickCount
                entity.lastFireAt = target.position()
                if (entity.tickCount >= nextAlarmTick) {
                    nextAlarmTick = entity.tickCount + ALARM_INTERVAL_TICKS
                    Alarm.raise(entity, entity.position(), target.position(), GUNFIRE_HEARING_RADIUS)
                    // The other side hears it too — here rather than off SBW's shot event, which a
                    // shot nobody watches (OffscreenFire) never raises.
                    (entity.level() as? ServerLevel)?.let { com.sbwnpc.squad.combat.Hearing.gunshot(it, entity, gun.hearingRadius) }
                }
                if (fireMode == TriggerMode.AUTO && roundsInBurst >= burstLimit) {
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

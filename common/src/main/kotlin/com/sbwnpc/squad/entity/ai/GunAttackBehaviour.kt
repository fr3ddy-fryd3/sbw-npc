package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.ai.integration.FiringSpotSearch
import com.sbwnpc.squad.combat.Alarm
import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.combat.DarkAim
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.DroneCombat
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.OffscreenFire
import com.sbwnpc.squad.combat.ShotAim
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.WeaponReach
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.domain.port.HandGun
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.TriggerMode
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcPosture
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.util.MillisTimer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * Aiming and shooting at the target, in the Fight activity (`NpcEntity.getFightTasks()`), which
 * `BrainActivityGroup.fightTasks()` runs only while `MemoryModuleType.ATTACK_TARGET` is set.
 *
 * It moves the man only for the ammunition rule, the walk to a Supply. Every other move in the
 * fight is his plan's (#251 step B), a retreat's bounds too (#253): his position or cover action,
 * or his engage, which an engage-only agent plans for a man with no plan of his own. What the gun
 * sees on the way, the clear shots, the lane checks and the opening fire, it records in
 * [NpcEntity.fireObservation] for his plan's facts.
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

    private var lineIsClear = true
    private var blastClear = true

    // Spread actually used for the current target — wider than entity.spread when the target is
    // a drone (or riding one), see DroneCombat.spreadForTarget. Cached alongside the friendly-fire
    // check so both use the same value a shot will actually be fired with.
    private var shotSpread = 0.0

    private var nextFriendlyFireCheckTick = 0
    private var nextAlarmTick = 0

    // See OffscreenFire — re-checked every WITNESS_CHECK_INTERVAL ticks, not per shot.
    private var nextWitnessCheckTick = 0
    private var witnessed = true

    // Sustained-fire pacing for AUTO weapons — see the shoot block in tick().
    private val automaticBurst = com.sbwnpc.squad.combat.AutomaticBurst()

    companion object {
        private const val LAUNCHER_SPREAD_FACTOR = 0.25
        private const val WITHDRAW_SPEED = 1.3

        /** Inside a Supply's reach, so the next issue round catches him. */
        private const val SUPPLY_ARRIVE_DISTANCE = com.sbwnpc.squad.block.entity.SupplyBlockEntity.RADIUS - 3.0

        // Friendly-fire assessment used to be two entity queries EVERY tick for every shooter (line
        // of fire + blast radius). Now one combined pass every few ticks — an ally can't cross a
        // firing lane in under a fifth of a second, and the cached answer is what gates the shot.
        private const val FRIENDLY_FIRE_CHECK_INTERVAL = 4

        /** One lane check in this many logs the dark spread: they run every [FRIENDLY_FIRE_CHECK_INTERVAL] ticks. */
        private const val DARK_SPREAD_SAMPLE = 10

        // Alarm.raise used to fire on EVERY shot (an MG at 600 RPM = 10 ally scans per second per
        // shooter). The alert it raises lasts 200 ticks and re-raising only extends the timer, so
        // once a second is indistinguishable in effect.
        private const val ALARM_INTERVAL_TICKS = 20
        private const val WITNESS_CHECK_INTERVAL = 20

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    private val NpcEntity.maxAimTime get() = npcRank.aimTimeTicks
    private val NpcEntity.semiFireInterval get() = npcRank.semiFireIntervalMs

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
        if (!wantLauncher) SidearmKit.update(entity)
    }

    private fun currentGun(entity: NpcEntity): HandGun? = Ports.guns.inHand(entity)

    private fun canEngage(entity: NpcEntity): Boolean {
        if (entity.busyWithRole()) return false
        if (entity.holdsAny(NpcActivity.COVER, NpcActivity.EVADE, NpcActivity.HEAL)) return false
        val target = entity.target ?: return false
        if (entity.incomingFire.replying(entity.level().gameTime) && !canTrackTarget(entity, target)) return false
        val gun = currentGun(entity) ?: return false
        return target.isAlive && gun.hasAmmo()
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = canEngage(entity)

    /** Still able to fight — or, with ammo left, still on its way somewhere. */
    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        if (entity.busyWithRole()) return false
        if (canEngage(entity)) return true
        // Cover, a medic's treatment or AntiDroneBehaviour (which owns the gun and the feet while
        // a hostile drone is inbound) take the mob over; nothing to finish walking to then.
        if (entity.holdsAny(NpcActivity.COVER, NpcActivity.EVADE, NpcActivity.HEAL, NpcActivity.ANTI_DRONE)) return false
        return !entity.navigation.isDone && currentGun(entity)?.hasAmmo() == true
    }

    override fun start(entity: NpcEntity) {
        entity.isAggressive = true
    }

    override fun stop(entity: NpcEntity) {
        entity.readyToCover = false
        // The fight is over; a gunner left holding a launcher would meet the next rifleman with it.
        AntiArmourKit.wield(entity, launcher = false)
        entity.blockedSightSince = null
        entity.isAggressive = false
        entity.stopUsingItem()
        aimTime = 0
        entity.debugAimTicks = 0
        entity.firingObstruction.reset()
        entity.fireObservation.reset()
        shootTimer.stop()
        lineIsClear = true
        blastClear = true
        nextFriendlyFireCheckTick = 0
        nextWitnessCheckTick = 0
        witnessed = true
        automaticBurst.reset()
    }

    private fun rocketAimPoint(target: LivingEntity): Vec3 = target.vehicle?.boundingBox?.center ?: target.position().add(0.0, target.bbHeight * 0.3, 0.0)

    override fun tick(entity: NpcEntity) {
        val target = entity.target ?: return
        val replyingToIncoming = entity.incomingFire.replying(entity.level().gameTime)
        if (replyingToIncoming && !canTrackTarget(entity, target)) return

        val visibility = DetectionSightline.trace(entity, target)
        val visible = visibility == DetectionSightline.Visibility.VISIBLE
        val foliageHidden = visibility == DetectionSightline.Visibility.FOLIAGE
        // Do not fight the CORE executor by repeatedly swapping back to a launcher for a hidden tank.
        if (foliageHidden) AntiArmourKit.wield(entity, launcher = false) else chooseWeapon(entity, target)
        val gun = currentGun(entity) ?: return
        // His plan moves him in the fight; one with no plan of his own gets an engage-only one.
        SquadTactics.engaging(entity)
        val terrainClear = hasClearShot(entity, target)
        val clearShot = visible && terrainClear && WeaponReach.inRange(entity, entity.eyePosition, target.eyePosition)
        if (visible) reportVisibleTarget(entity, target) else entity.noticeHidden(target, visibility)
        updateAim(entity, target, gun, clearShot, visibility)

        val ammunitionRule = followAmmunitionRules(entity)
        // A target dipping behind terrain for a moment is no reason to leave the post: only a
        // sustained obstruction asks the tactical executor for another one.
        if (!terrainClear && !ammunitionRule && entity.firingObstruction.requiresRecovery) {
            SquadTactics.repositionForFire(entity)
            entity.firingObstruction.reset()
        }
        // The CORE blind-fire executor owns the gun and cached aim while vegetation hides the target.
        if (foliageHidden) {
            entity.firingObstruction.observe(false)
            return
        }
        // Movement uses the previous safety assessment; refresh it before the next shot.
        updateFiringLane(entity, target, gun)
        entity.firingObstruction.observe(clearShot && lineIsClear && blastClear)
        entity.fireObservation.look(target.uuid, entity.level().gameTime, clearShot && lineIsClear && blastClear, gun.triggerMode == TriggerMode.AUTO, entity.currentSquad()?.orderStamp)
        if (!ammunitionRule) askForAnotherPost(entity)
        gun.operate()
        tickShooting(entity, target, gun, clearShot)
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

    private fun updateAim(entity: NpcEntity, target: LivingEntity, gun: HandGun, canShootTarget: Boolean, visibility: DetectionSightline.Visibility) {
        val foliageHidden = visibility == DetectionSightline.Visibility.FOLIAGE
        if (canShootTarget || foliageHidden) {
            entity.blockedSightSince = null
        } else if (entity.blockedSightSince == null) {
            // Stamped once, on the tick sight was lost — GrenadeUseBehaviour measures from here.
            entity.blockedSightSince = entity.tickCount
        }
        // Losing sight starts the aim over.
        aimTime = if (canShootTarget) minOf(entity.maxAimTime, aimTime + 1) else 0
        entity.debugAimTicks = aimTime

        if (foliageHidden || (visibility != DetectionSightline.Visibility.VISIBLE && entity.checkingNearbyNoise())) {
            entity.readyToCover = false
            shootTimer.stop()
            return
        }
        entity.lookAt(target, 30f, 30f)
        // Rendered facing can lag behind aim while MoveControl turns the body along a path.
        entity.lookControl.setLookAt(target.x, target.eyeY, target.z)
        // Arc compensation uses the hull/low body point rather than the target eyes.
        gun.arcPitch(entity.eyePosition, rocketAimPoint(target))?.let { entity.xRot = it }
    }

    /**
     * The ammunition rule (AGENTS.md: it stays as it is): the walk to a Supply when low. True while
     * it has his feet; otherwise his plan moves him, a retreat included (#253), and nothing here
     * does. At the Supply he stands and fires.
     */
    private fun followAmmunitionRules(entity: NpcEntity): Boolean {
        val resupplyAt = entity.lowAmmoFallback() ?: return false
        if (entity.position().distanceTo(resupplyAt) > SUPPLY_ARRIVE_DISTANCE) entity.navigateTo(resupplyAt, WITHDRAW_SPEED) else entity.navigation.stop()
        return true
    }

    private fun updateFiringLane(entity: NpcEntity, target: LivingEntity, gun: HandGun, force: Boolean = false, aimPoint: Vec3 = SquadTactics.firingLaneEnd(entity, target)) {
        if (force || entity.tickCount >= nextFriendlyFireCheckTick) {
            nextFriendlyFireCheckTick = entity.tickCount + FRIENDLY_FIRE_CHECK_INTERVAL
            val explosionRadius = gun.explosionRadius
            val dark = DarkAim.spreadFactor(entity.level(), target)
            shotSpread = DroneCombat.spreadForTarget(entity.spread * dark * NpcPosture.spreadFactor(entity), target)
            if (dark > 1.0 && entity.random.nextInt(DARK_SPREAD_SAMPLE) == 0) {
                DebugFlags.log(LogGroup.FIRE, "{} aims at {} in the dark: spread x{} -> {}", entity.uuid, target.uuid, "%.2f".format(dark), "%.2f".format(shotSpread))
            }
            val assessment = FriendlyFireGuard.assess(
                entity,
                aimPoint,
                shotSpread,
                target.position(),
                explosionRadius,
            )
            // A vehicle in the way counts as "line not clear" too, but it is answered differently
            // from an ally in the way — see the block further down.
            val level = entity.level() as ServerLevel
            val lane = AABB(entity.eyePosition, aimPoint).inflate(FiringSpotSearch.VEHICLE_LANE_MARGIN)
            val hulls = Sightline.vehicleHulls(level, lane, entity, target)
            val hullInTheWay =
                Sightline.crosses(hulls, entity.eyePosition, aimPoint, shotSpread) ||
                    Sightline.ownHullInLane(entity, entity.eyePosition, aimPoint, shotSpread, target)
            lineIsClear = assessment.lineClear && !hullInTheWay
            entity.fireObservation.lane(assessment.lineClear, hullInTheWay)
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

    /**
     * A line of fire an ally or a hull keeps blocked asks his plan's position for another post,
     * once the block persists: an ally crossing the lane clears it again. An engage answers the
     * same block itself, from the lane checks [FireObservation] keeps. Not from a hole, nor while
     * withdrawing or peeking from cover.
     */
    private fun askForAnotherPost(entity: NpcEntity) {
        val holdsPosition = entity.holds(NpcActivity.DUG_IN) || entity.holds(NpcActivity.PEEK)
        if (!lineIsClear && !holdsPosition && entity.firingObstruction.requiresRecovery) {
            SquadTactics.repositionForFire(entity)
            entity.firingObstruction.reset()
        }
    }

    private enum class FireBlocker { WEAPON_RANGE, SIGHT_BLOCKED, ARMOUR, BURST_PAUSE, FRIENDLY_OR_VEHICLE, BLAST_SAFETY, WEAPON_CYCLE, AIMING }
    private var lastFireBlocker: FireBlocker? = null
    private var lastFireTarget: java.util.UUID? = null

    private fun fireBlocker(entity: NpcEntity, target: LivingEntity, gun: HandGun, clearShot: Boolean, pausedBetweenBursts: Boolean): FireBlocker? =
        when {
            !WeaponReach.inRange(entity, entity.eyePosition, target.eyePosition) -> FireBlocker.WEAPON_RANGE
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
        DebugFlags.log(
            LogGroup.ORDER,
            "[tactics] {} squad={} event=fire_gate target={} reason={} distance={}",
            entity.uuid,
            squad?.id,
            target.uuid,
            blocker ?: "READY",
            entity.distanceTo(target),
        )
    }

    private fun tickShooting(entity: NpcEntity, target: LivingEntity, gun: HandGun, canShootTarget: Boolean) {
        val pausedBetweenBursts = gun.triggerMode == TriggerMode.AUTO && automaticBurst.paused(entity.tickCount)
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
            cooldown = entity.fireIntervalMillis(gun, cooldown)
            // A bolt or reload stops shootTimer; becoming mechanically ready must not grant
            // a fresh opening shot before this class's interval since the previous shot.
            if (entity.fireIntervalMultiplier > 1 && entity.tickCount - entity.lastShotTick < kotlin.math.ceil(cooldown / 50.0).toInt()) return

            if (!shootTimer.started()) {
                shootTimer.start()
                shootTimer.progress = cooldown + 1
            }

            if (shootTimer.progress >= cooldown) {
                val from = entity.eyePosition
                val direction = ShotAim.direction(from, target.eyePosition, gun.arcPitch(from, rocketAimPoint(target)))
                val laneLength = SquadTactics.firingLaneEnd(entity, target).distanceTo(from)
                // Cached answers guide movement, but cannot authorise a later shot: an ally
                // may have entered the lane since that check. Check the actual weapon aim now.
                updateFiringLane(entity, target, gun, force = true, aimPoint = from.add(direction.scale(laneLength)))
                if (!lineIsClear || !blastClear) {
                    entity.readyToCover = false
                    traceFireBlocker(entity, target, if (!lineIsClear) FireBlocker.FRIENDLY_OR_VEHICLE else FireBlocker.BLAST_SAFETY)
                    shootTimer.stop()
                    return
                }
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
                        gun.shootAt(spread, zoom = false, target.uuid, direction)
                    }
                    newProgress -= cooldown
                    if (fireMode == TriggerMode.AUTO) automaticBurst.fired(entity.npcClass, entity.tickCount, entity.random)
                    entity.fireObservation.fired()
                } while (newProgress - cooldown > 0 && (fireMode != TriggerMode.AUTO || !automaticBurst.paused(entity.tickCount)))
                shootTimer.progress = newProgress
                entity.lastShotTick = entity.tickCount
                entity.firingObstruction.reset()
                entity.lastFireAt = target.position()
                if (entity.tickCount >= nextAlarmTick) {
                    nextAlarmTick = entity.tickCount + ALARM_INTERVAL_TICKS
                    Alarm.raise(entity, entity.position(), target.position(), Hearing.ALLIED_GUNSHOT_RADIUS)
                    // The other side hears it too — here rather than off SBW's shot event, which a
                    // shot nobody watches (OffscreenFire) never raises.
                    (entity.level() as? ServerLevel)?.let { com.sbwnpc.squad.combat.Hearing.gunshot(it, entity, gun.hearingRadius, gun.suppressed) }
                }
            }
        } else {
            shootTimer.stop()
        }
    }
}

package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DroneAccuracy
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.entity.DroneRegistry
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID
import kotlin.math.roundToInt

/**
 * How infantry reacts to a hostile drone (SBW `DroneEntity` or any Drone Warfare addon subclass —
 * a player's, or another squad's operator's). Core task: it must preempt an ongoing firefight,
 * since an FPV drone that gets close ends the firefight for everyone in a 12-block radius.
 *
 * Detection ([detect], every [DETECT_INTERVAL] ticks): a hostile drone within [SIGHT_RANGE] with
 * line of sight, or within [HEARING_RANGE] with its motor running (they are loud) — line of sight
 * not required. Whoever notices shouts: every ally within [ALERT_RADIUS] gets the drone's id as a
 * [ModMemories.DRONE_THREAT] TTL memory, so a squad reacts together rather than one by one as
 * each spots it.
 *
 * Reaction by class (per user decision):
 *  - RIFLEMAN / MACHINE_GUNNER / SNIPER / TANK_CREW (on foot) / DRONE_OPERATOR (not flying): plant
 *    feet and **shoot it down** — aim with lead on its velocity, SBW's normal spread applies, so a
 *    drone crossing at speed is a hard target; the addon's FPV drone dies to one hit. Inside
 *    [SHOOTER_PANIC_RANGE] it's too late to shoot: break and run ([evade]).
 *  - MEDIC / GRENADIER / MORTAR crew: **take cover** — the drone's position becomes a suppression
 *    threat and [SeekCoverBehaviour] does what it already does (a roof overhead is the only real
 *    answer to an FPV); inside [HIDER_PANIC_RANGE] with the drone closing, run instead.
 *  - An operator flying its own drone stays on the monitor (not eligible).
 *
 * `NpcEntity.antiDroneEngaged` locks GunAttack/grenades/orders/investigating/vehicles for the
 * duration, and keeps the AI LOD at full rate. The threat is dropped when the drone is dead,
 * gone, out of range, or the memory expires.
 */
class AntiDroneBehaviour : ExtendedBehaviour<NpcEntity>() {

    init {
        noTimeout()
    }

    private var nextDetectTick = 0
    private var nextShotTick = 0
    private var evadeUntilTick = 0
    private var evadeTarget: Vec3? = null
    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun eligible(entity: NpcEntity): Boolean =
        !entity.vehicleTransport && !entity.operatingDrone

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { eligible(entity) && threat(entity, level) != null }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        val level = entity.level() as? ServerLevel ?: return false
        return eligible(entity) && threat(entity, level) != null
    }

    override fun start(entity: NpcEntity) {
        entity.antiDroneEngaged = true
        entity.navigation.stop()
        evadeUntilTick = 0
        evadeTarget = null
    }

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        entity.antiDroneEngaged = false
        entity.stopUsingItem()
        entity.navigation.stop()
    }

    // --- detection ---

    private fun isShooter(entity: NpcEntity): Boolean = when (entity.npcClass) {
        NpcClass.RIFLEMAN, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.TANK_CREW, NpcClass.DRONE_OPERATOR -> true
        // Helicopter crew carry a sidearm and are strapped into an aircraft; the gunship's own
        // turret is what deals with a drone, through the ordinary targeting path.
        NpcClass.MEDIC, NpcClass.GRENADIER, NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER,
        NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER -> false
    }

    /** The drone this mob should be dealing with right now: the remembered one if still a live,
     *  hostile, in-range threat; otherwise a fresh detection (rate limited). */
    private fun threat(entity: NpcEntity, level: ServerLevel): DroneEntity? {
        BrainUtils.getMemory(entity, ModMemories.DRONE_THREAT.get())?.let { id ->
            val drone = level.getEntity(id) as? DroneEntity
            if (drone != null && isLiveThreat(entity, drone, TRACK_RANGE)) return drone
        }
        if (entity.tickCount < nextDetectTick) return null
        nextDetectTick = entity.tickCount + DETECT_INTERVAL
        val found = detect(entity, level) ?: return null
        remember(entity, found)
        shout(entity, level, found)
        DebugFlags.log("[drone-debug] {} spotted hostile drone {}", entity.uuid, found.uuid)
        return found
    }

    private fun isLiveThreat(entity: NpcEntity, drone: DroneEntity, range: Double): Boolean =
        drone.isAlive && !drone.isWreck && SquadTeams.isHostile(entity, drone) &&
            entity.distanceToSqr(drone) <= range * range

    private fun detect(entity: NpcEntity, level: ServerLevel): DroneEntity? {
        var best: DroneEntity? = null
        var bestD2 = Double.MAX_VALUE
        for (drone in DroneRegistry.all(level)) {
            if (!isLiveThreat(entity, drone, SIGHT_RANGE)) continue
            val d2 = entity.distanceToSqr(drone)
            if (d2 >= bestD2) continue
            val heard = d2 <= HEARING_RANGE * HEARING_RANGE && drone.engineRunning()
            if (heard || entity.sensing.hasLineOfSight(drone)) {
                best = drone
                bestD2 = d2
            }
        }
        return best
    }

    private fun remember(npc: NpcEntity, drone: DroneEntity) {
        BrainUtils.setForgettableMemory(npc, ModMemories.DRONE_THREAT.get(), drone.uuid, THREAT_TTL_TICKS)
    }

    /** "Drone!" — hand the sighting to every ally in earshot. They get it as a memory, so their
     *  own AntiDroneBehaviour starts on its next tick without needing to see it themselves. */
    private fun shout(entity: NpcEntity, level: ServerLevel, drone: DroneEntity) {
        NpcRegistry.forEachWithin(level, entity.position(), ALERT_RADIUS, exclude = entity) { ally ->
            if (!SquadTeams.isHostile(entity, ally) && !BrainUtils.hasMemory(ally, ModMemories.DRONE_THREAT.get())) {
                remember(ally, drone)
            }
        }
    }

    // --- reaction ---

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val drone = threat(entity, level) ?: return
        val dist = entity.distanceTo(drone).toDouble()
        val closing = drone.deltaMovement.dot(entity.position().subtract(drone.position())) > 0.0

        if (isShooter(entity)) {
            if (dist <= SHOOTER_PANIC_RANGE && closing) {
                evade(entity, drone)
                return
            }
            if (tickEvade(entity)) return
            shoot(entity, level, drone, dist)
        } else {
            // Cover-seekers: hand the drone to SeekCoverBehaviour as a suppression threat (it
            // finds a spot the threat position can't see — for a drone, that means a roof).
            if (dist <= HIDER_PANIC_RANGE && closing && !entity.combatLockedByCover()) {
                evade(entity, drone)
                return
            }
            if (tickEvade(entity)) return
            entity.suppress(drone.position())
        }
    }

    /** Sprint a few blocks directly away from the drone; re-planned once the burst is over. */
    private fun evade(entity: NpcEntity, drone: DroneEntity) {
        if (entity.tickCount < evadeUntilTick && evadeTarget != null) return
        val away = entity.position().subtract(drone.position()).let { Vec3(it.x, 0.0, it.z) }
        val dir = if (away.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else away.normalize()
        // A little sideways so a squad doesn't all sprint down the drone's own line of approach.
        val side = Vec3(-dir.z, 0.0, dir.x).scale((entity.random.nextDouble() - 0.5) * EVADE_DISTANCE)
        val target = entity.position().add(dir.scale(EVADE_DISTANCE)).add(side)
        evadeTarget = target
        evadeUntilTick = entity.tickCount + EVADE_TICKS
        entity.navigateTo(target, EVADE_SPEED, repathIntervalTicks = 5)
    }

    /** True while an evade burst is still running (keeps the mob moving, nothing else). */
    private fun tickEvade(entity: NpcEntity): Boolean {
        val target = evadeTarget ?: return false
        if (entity.tickCount >= evadeUntilTick) {
            evadeTarget = null
            entity.navigation.stop()
            return false
        }
        entity.navigateTo(target, EVADE_SPEED, repathIntervalTicks = 5)
        return true
    }

    private fun shoot(entity: NpcEntity, level: ServerLevel, drone: DroneEntity, dist: Double) {
        entity.navigation.stop()
        val gun = Ports.guns.inHand(entity) ?: return

        // Lead: where the drone will be when the round gets there (VELOCITY is blocks/tick).
        val velocity = gun.muzzleVelocity.coerceAtLeast(1.0)
        val aim = drone.position().add(0.0, drone.bbHeight * 0.5, 0.0).add(drone.deltaMovement.scale(dist / velocity))
        entity.lookAt(EntityAnchorArgument.Anchor.EYES, aim)
        entity.lookControl.setLookAt(aim.x, aim.y, aim.z)

        gun.operate()

        if (dist > SHOOT_RANGE || !entity.sensing.hasLineOfSight(drone)) return
        if (entity.tickCount < nextShotTick || !gun.canShoot()) return
        // A drone is a small, fast, evasive target — the same aim a rifleman holds on a person
        // shouldn't land on it as reliably.
        val spread = DroneAccuracy.adjustSpread(entity.npcRank.spread * entity.npcClass.accuracyMultiplier, true)
        if (!FriendlyFireGuard.hasClearLineOfFire(entity, aim, spread)) return

        gun.shootAt(spread, aim)
        entity.lastShotTick = entity.tickCount

        var cooldownTicks = (1200.0 / gun.roundsPerMinute.coerceAtLeast(1.0)).roundToInt().coerceAtLeast(1)
        if (gun.needsTriggerReset) {
            cooldownTicks += (entity.npcRank.semiFireIntervalMs / 50).toInt()
        }
        nextShotTick = entity.tickCount + cooldownTicks
    }

    companion object {
        private const val START_CHECK_INTERVAL_TICKS = 5
        private const val DETECT_INTERVAL = 10
        private const val SIGHT_RANGE = 48.0
        private const val HEARING_RANGE = 25.0
        /** A remembered drone stays a threat a bit past sight range so it isn't re-detected every scan. */
        private const val TRACK_RANGE = 64.0
        private const val ALERT_RADIUS = 30.0
        private const val THREAT_TTL_TICKS = 60

        private const val SHOOT_RANGE = 40.0
        private const val SHOOTER_PANIC_RANGE = 6.0
        private const val HIDER_PANIC_RANGE = 12.0
        private const val EVADE_DISTANCE = 8.0
        private const val EVADE_TICKS = 30
        private const val EVADE_SPEED = 1.2
    }
}

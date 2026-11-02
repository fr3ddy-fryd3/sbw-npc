package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.Hostiles
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Noticing
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.Vision
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.DroneFlightController
import com.sbwnpc.squad.entity.ai.DroneLaunchWalk
import com.sbwnpc.squad.experience.ExperienceAwards
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.SquadReports
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** A strike target as the station flies it: a man ([entityId]) or a fixed point, last known at [pos]. */
internal class StrikeTarget(val entityId: UUID?, val pos: Vec3)

/**
 * A DRONE_OPERATOR's station (#259): his eyes for strike targets, and his drone. The plan
 * chooses what the drone goes for (`DroneStrike`); the station launches it there and flies it,
 * from the server, no Monitor/player needed.
 *
 * The drone: with the SBW Drone Warfare addon installed, its FPV drone (`sbwdroneconfig:
 * cubed_fpv_drone`, "the drone itself is the weapon") — spawned by registry id, so the addon is
 * an optional runtime dependency, not a compile one. The addon's `DroneEntityCrashExplosionMixin`
 * explodes ANY drone on `destroy()` unless SBW's kamikaze flag is set, so we set no flag and mount
 * no payload: detonating at the target is just `destroy()`, and being shot down or crashing
 * explodes it exactly as a player-flown one would (its config, sounds, particles). Without the
 * addon: SBW's bare drone with nothing mounted (a mounted rocket is drawn under it) and our own
 * `CustomExplosion` from a kamikaze attachment's datapack entry — SBW's `kamikazeExplosion`
 * silently does nothing without a player controller anyway — on impact and when shot down alike.
 *
 * Flying: `DroneEntity` moves from its public input flags (see [DroneFlightController] for what
 * each one really does); we write them every tick and set `yRot` directly, since without a
 * player at the monitor nothing else turns the drone. `Controller` stays unset — every
 * `controller != null` branch in `DroneEntity` is a player convenience (monitor position sync,
 * unlink, messages). The addon's FPV input replay (`CubedFpvInputState.applyRecentInput`) only
 * touches drones a player has sent input for, so it never fights ours.
 *
 * Phases: LAUNCH (climb to clearance over the operator) -> CRUISE (heightmap look-ahead keeps it
 * above trees/roofs, closes on the target's live position) -> ATTACK (dive on the target's body,
 * detonate within [DETONATE_RANGE] or on impact with ground/blocks) — with HOLD (circle at altitude
 * while allies are inside the blast radius, or while the plan picks a new target for one lost) and
 * RETURN (the plan sends it home: fly back, land, refund the drone). Shot down / timed out -> blast
 * where it is. Operator killed -> [onOperatorDied]: same, "signal lost".
 *
 * Mandatory rules that stay here, not in the plan: the impact fuze, holding off while an ally is
 * in the blast, the battery's end, and signal lost on the operator's death or on his boarding.
 *
 * The flight outlives a replan: a plan that drops his strike action leaves the drone flying, and
 * the next one takes it up again, since a cancel that blew it up would turn every new squad order
 * into a crash. [keepFlying] flies it on a tick no action did.
 *
 * One drone per operator, [MAX_DRONES] carried, refilled at a barracks
 * (SquadManager.resupplyAtBarracks). Under broad cover he first walks to a reachable launch column
 * nearby and returns to his original post after launch. While flying the operator stands still
 * holding the Monitor (its gun is stowed in `NpcEntity.stowedWeapon`): his plan's other families
 * stand aside while the drone is up, and the behaviours outside the plan stand down via the
 * [NpcActivity.DRONE] claim.
 */
internal class DroneStation(val operator: NpcEntity) {
    private enum class Phase { LAUNCH, CRUISE, ATTACK, HOLD, RETURN }

    /** How a tick of the station ended for the strike action that drives it. */
    enum class Outcome { RUNNING, ENDED, FAILED }

    private var droneId: UUID? = null
    private var launchWalk: DroneLaunchWalk? = null
    private var pending: StrikeTarget? = null
    private var phase = Phase.LAUNCH
    private var targetEntityId: UUID? = null
    private var targetPos: Vec3 = Vec3.ZERO

    /** The plan has not yet given a drone whose target was lost a new one. */
    private var lost = false
    private var lastDronePos: Vec3? = null
    private var launchTick = 0
    private var nextLaunchTick = 0
    private var holdUntilTick = 0
    private var flownAt = -1L

    /** When the drone, or anyone on its side, last saw the target it is after. */
    private var sawTargetTick = 0
    private var nextSightCheckTick = 0
    private var clearance = CRUISE_CLEARANCE_MIN
    private var detonated = false
    private var usesAddonBlast = false

    /** Targets this flight has given up on (allies kept standing in the blast, out of sight), so
     *  the plan's next choice doesn't pick the same one straight back up and hold over it again. */
    private val rejected = HashSet<UUID>()

    /** When the returning drone first came within [APPROACH_RADIUS] of home, or null. */
    private var approachSinceTick: Int? = null

    private var nextScanTick = 0
    private var lastSpotted: StrikeTarget? = null

    /** How far the operator has got at noticing each enemy his own scan looks at. */
    private val noticing = Noticing("drone operator", LogGroup.FIRE_SUPPORT)

    private val level get() = operator.level() as ServerLevel

    init {
        // A world saved mid-flight comes back with no drone up and the monitor still in hand.
        restoreWeapon()
    }

    val airborne get() = droneId != null

    /** He waits out the pause after a flight, or after a walk that found no open sky. */
    val coolingDown get() = operator.tickCount < nextLaunchTick

    /** The target of the drone in the air, while it has one the plan gave it and has not lost. */
    val flying: StrikeTarget? get() = if (airborne && !lost && phase != Phase.RETURN) StrikeTarget(targetEntityId, targetPos) else null

    // --- target sensing, the plan's facts ---

    private fun maxRange(): Double {
        val t = operator.npcRank.ordinal / (NpcRank.entries.size - 1).toDouble()
        return MIN_MAX_RANGE + t * (MAX_MAX_RANGE - MIN_MAX_RANGE)
    }

    /** Within his launch envelope: [MIN_RANGE] out to his rank's reach. */
    fun inLaunchRange(pos: Vec3): Boolean {
        val d2 = operator.position().distanceToSqr(pos)
        val max = maxRange()
        return d2 >= MIN_RANGE * MIN_RANGE && d2 <= max * max
    }

    /** An enemy at arm's length: he shoots it, he does not launch. */
    fun threatened(): Boolean = operator.target?.let { it.isAlive && operator.distanceToSqr(it) <= SELF_DEFENSE_RANGE_SQR } == true

    /**
     * Whether [target] may be struck: with no ally in the blast, and from the air only one not
     * given up on. Checked on foot too: an ATTACK objective the infantry already stood on outranked
     * the enemy he saw himself, and every drone flew there, held over the allies and came home.
     */
    fun strikable(target: StrikeTarget): Boolean {
        if (!airborne) return FriendlyFireGuard.hasClearBlastRadius(operator, target.pos, maxOf(Ports.drones.warheadRadius(true), Ports.drones.warheadRadius(false)))
        if (target.entityId != null && target.entityId in rejected) return false
        // The ally check skips the operator itself, and a drone on its way home is close to him.
        val radius = warheadRadius()
        return target.pos.distanceTo(operator.position()) > radius && FriendlyFireGuard.hasClearBlastRadius(operator, target.pos, radius)
    }

    /**
     * An enemy he saw himself, or with the drone up the nearest one its camera sees around it.
     * His own eyes report to the faction like the mortar does, at the same rate whether he
     * launches or not. Cached for [SCAN_INTERVAL_TICKS].
     */
    fun spotted(): StrikeTarget? {
        if (operator.tickCount < nextScanTick) return lastSpotted
        nextScanTick = operator.tickCount + SCAN_INTERVAL_TICKS
        val own = scan()
        val drone = droneId?.let(level::getEntity)
        lastSpotted = if (drone != null) droneSighted(drone) else own
        return lastSpotted
    }

    private fun scan(): StrikeTarget? {
        val tick = level.gameTime
        val faction = SquadTeams.factionOf(operator)
        var spotted: LivingEntity? = null
        var losChecks = 0
        for (c in Hostiles.within(level, operator, maxRange())) {
            // Range first: candidates are sorted nearest-first, and everything inside MIN_RANGE
            // used to spend the raycast budget before a single launchable target got looked at.
            if (!inLaunchRange(c.position())) continue
            // Own eyes are a man's eyes (#114): where he looks, and only once he has noticed it. A
            // bare line-of-sight check launched on an enemy 150 blocks off the moment it was in view.
            if (!Vision.inCone(operator.position(), operator.yHeadRot, c.position())) continue
            if (losChecks++ >= MAX_LOS_CHECKS) break
            if (DetectionSightline.sight(operator, c).visibility != DetectionSightline.Visibility.VISIBLE) continue
            if (!noticing.noticed(operator, level, c, Vision.offAxis(operator.position(), operator.yHeadRot, c.position()))) continue
            if (faction != null) TeamAwareness.report(faction, c.uuid, c.position(), tick, "drone operator ${operator.uuid.toString().take(8)}")
            // Every one he notices is reported; the nearest he may strike is his, not the nearest
            // of all: one with allies on him dropped the whole of his own sighting.
            if (spotted == null && strikable(StrikeTarget(c.uuid, c.position()))) spotted = c
        }
        noticing.endScan(tick)
        spotted ?: return null
        // Called in the way a rifleman calls it, so the player hears of the fight before the drone is up.
        SquadReports.contact(operator, spotted)
        DebugFlags.log(LogGroup.FIRE_SUPPORT, "drone {} -> {} seen by itself at {}", operator.uuid, spotted.uuid, spotted.position())
        return StrikeTarget(spotted.uuid, spotted.position())
    }

    /** The nearest strikable hostile around the drone its camera sees: no operator line of sight needed. */
    private fun droneSighted(drone: Entity): StrikeTarget? {
        val near = ArrayList<LivingEntity>()
        NpcRegistry.forEachWithin(level, drone.position(), RETARGET_RADIUS) { near += it }
        val r2 = RETARGET_RADIUS * RETARGET_RADIUS
        for (player in level.players()) if (player.distanceToSqr(drone) <= r2) near += player
        near.sortBy { it.distanceToSqr(drone) }
        var checks = 0
        for (c in near) {
            if (!c.isAlive || !SquadTeams.isHostile(operator, c) || !strikable(StrikeTarget(c.uuid, c.position()))) continue
            if (checks++ >= MAX_LOS_CHECKS) break
            if (droneSees(drone, c)) return StrikeTarget(c.uuid, c.position())
        }
        return null
    }

    /** Where his side last saw an enemy in his reach, the nearest such report: the drone's camera takes over once it is there. */
    fun reported(): StrikeTarget? = known().minByOrNull { operator.position().distanceToSqr(it.pos) }

    /**
     * The enemy his side knows nearest [point], within [radius] of it. An ATTACK's point alone is
     * no target: struck bare, every drone blew up on empty ground where nobody had seen anyone.
     */
    fun reportedAt(point: Vec3, radius: Double): StrikeTarget? = known().filter { it.pos.distanceTo(point) <= radius }.minByOrNull { it.pos.distanceToSqr(point) }

    /** Every enemy his side has reported that is alive, in his reach and strikable. */
    private fun known(): Sequence<StrikeTarget> {
        val faction = SquadTeams.factionOf(operator) ?: return emptySequence()
        return TeamAwareness.sightings(faction, level.gameTime).asSequence()
            .filter { s ->
                val e = level.getEntity(s.target) as? LivingEntity
                e != null && e.isAlive && SquadTeams.isHostile(operator, e) && inLaunchRange(s.pos) && strikable(StrikeTarget(s.target, s.pos))
            }
            .map { StrikeTarget(it.target, it.pos) }
    }

    /**
     * Where the drone can go up from: [LAUNCH_HEIGHT] over the operator's feet, right above him or a
     * step to one side, with the air clear straight up to past cruise height.
     *
     * Put down a block and a half in front of him, an operator in cover put it into the wall he hid
     * behind, and it crashed on the spot and killed him (#158). The climb ([tickLaunch]) goes
     * straight up before turning, so a clear column is the whole way out.
     */
    private fun launchPoint(feet: Vec3): Vec3? {
        val base = feet.add(0.0, LAUNCH_HEIGHT, 0.0)
        val candidates = sequenceOf(base) + (0 until LAUNCH_SIDE_POINTS).asSequence().map {
            val angle = it * 2.0 * Math.PI / LAUNCH_SIDE_POINTS
            base.add(Math.cos(angle) * LAUNCH_SIDE_STEP, 0.0, Math.sin(angle) * LAUNCH_SIDE_STEP)
        }
        return candidates.firstOrNull(::openSky)
    }

    private fun openSky(at: Vec3): Boolean {
        // A roof higher than the climb checked below still stops it: the climb aims over the
        // tallest thing in this column. Floored, not [groundY]'s toInt, which reads the column
        // next door at negative coordinates.
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(at.x), Mth.floor(at.z)) > at.y) return false
        val box = AABB(at.x - LAUNCH_HALF_WIDTH, at.y, at.z - LAUNCH_HALF_WIDTH, at.x + LAUNCH_HALF_WIDTH, at.y + LAUNCH_BOX_HEIGHT, at.z + LAUNCH_HALF_WIDTH)
        return level.noCollision(operator, box.expandTowards(0.0, CRUISE_CLEARANCE_MAX, 0.0))
    }

    // --- the plan's duty ---

    /**
     * The plan's target for the drone, or home with [target] null. On foot this launches at it,
     * walking to open sky first where he has none; in the air it turns the drone onto it.
     */
    fun aim(target: StrikeTarget?) {
        if (!airborne) {
            pending = target
            if (target != null && launchWalk == null) prepare(target)
            return
        }
        if (target == null) {
            if (phase != Phase.RETURN) DebugFlags.log(LogGroup.DRONE, "{} drone sent home", operator.uuid)
            phase = Phase.RETURN
            return
        }
        if (flying?.let { same(it, target) } == true) return
        targetEntityId = target.entityId
        targetPos = target.pos
        sawTargetTick = operator.tickCount
        approachSinceTick = null
        lost = false
        if (phase != Phase.LAUNCH) phase = Phase.CRUISE
        DebugFlags.log(LogGroup.FIRE_SUPPORT, "drone {} retargeted to {} at {} by the plan", operator.uuid, target.entityId, target.pos)
    }

    private fun same(a: StrikeTarget, b: StrikeTarget) = a.entityId == b.entityId && (a.entityId != null || a.pos == b.pos)

    private fun prepare(target: StrikeTarget) {
        detonated = false
        if (launchPoint(operator.position()) != null) {
            launch(target)
        } else {
            launchWalk = DroneLaunchWalk(operator)
            operator.claim(NpcActivity.DRONE, this)
            operator.navigation.stop()
        }
    }

    /**
     * The plan no longer wants the strike. A walk to open sky ends, and the drone in the air flies
     * on until a plan takes it up again; only [abort] loses its signal.
     */
    fun release() {
        if (airborne) return
        abandonPreparation(retry = false)
    }

    /** Signal lost (operator boarded, dug in, gone): the drone comes down hard where it is. */
    fun abort() {
        val drone = droneId?.let { level.getEntity(it)?.takeIf(Ports.drones::isPiloted) }
        if (drone != null && !detonated) detonate(drone, drone.position())
        cleanup()
    }

    // --- launch / recover ---

    private fun launch(target: StrikeTarget) {
        // Which drone, and whose explosion — see the Drones adapter.
        val spawn = launchPoint(operator.position()) ?: return
        val drone = Ports.drones.createKamikaze(level)
        usesAddonBlast = Ports.drones.blowsUpOnCrash(drone)
        ExperienceAwards.launchedBy(drone, operator)
        drone.moveTo(spawn.x, spawn.y, spawn.z, operator.yRot, 0f)
        // Same scoreboard team as the operator: SBW turrets / hostile vehicles treat it as an enemy
        // vehicle, allies leave it alone.
        SquadTeams.factionOf(operator)?.let { SquadTeams.assign(drone, it) }
        level.addFreshEntity(drone)

        droneId = drone.uuid
        DroneLinks.link(operator.uuid, drone.uuid)
        targetEntityId = target.entityId
        targetPos = target.pos
        lost = false
        lastDronePos = drone.position()
        launchTick = operator.tickCount
        holdUntilTick = 0
        sawTargetTick = operator.tickCount
        nextSightCheckTick = 0
        detonated = false
        rejected.clear()
        approachSinceTick = null
        clearance = CRUISE_CLEARANCE_MIN + operator.random.nextDouble() * (CRUISE_CLEARANCE_MAX - CRUISE_CLEARANCE_MIN)
        phase = Phase.LAUNCH

        operator.dronesLeft--
        operator.claim(NpcActivity.DRONE, this)
        operator.navigation.stop()
        holdMonitor()
        DebugFlags.log(LogGroup.DRONE, "{} launched drone {} at {} ({} left)", operator.uuid, drone.uuid, target.pos, operator.dronesLeft)
    }

    private fun holdMonitor() {
        if (Ports.drones.isMonitor(operator.mainHandItem)) return
        Ports.guns.persist(operator.mainHandItem)
        operator.stowedWeapon = operator.mainHandItem.copy()
        operator.setItemInHand(InteractionHand.MAIN_HAND, Ports.drones.monitor())
    }

    private fun restoreWeapon() {
        if (operator.stowedWeapon.isEmpty) return
        operator.setItemInHand(InteractionHand.MAIN_HAND, operator.stowedWeapon)
        operator.stowedWeapon = ItemStack.EMPTY
    }

    private fun cleanup() {
        launchWalk?.cancel(operator)
        launchWalk = null
        pending = null
        if (droneId != null) {
            DroneLinks.unlink(operator.uuid)
            // Cooldown only after an actual flight — a launch that found nothing to launch at
            // shouldn't lock the operator out for the next 10-15 s.
            nextLaunchTick = operator.tickCount + LAUNCH_COOLDOWN_TICKS + operator.random.nextInt(LAUNCH_COOLDOWN_JITTER)
        }
        droneId = null
        targetEntityId = null
        lastDronePos = null
        lost = false
        operator.release(NpcActivity.DRONE, this)
        restoreWeapon()
    }

    /** Drone back over the operator: pick it up again, no explosion. */
    private fun recover(drone: Entity) {
        drone.discard()
        operator.dronesLeft++
        detonated = true // nothing left to blow up
        DebugFlags.log(LogGroup.DRONE, "{} recovered its drone", operator.uuid)
        cleanup()
    }

    // --- flight ---

    /** Flies the drone on a tick no strike action did, see the class doc. */
    fun keepFlying() {
        if (airborne && flownAt != level.gameTime) tick()
    }

    /** One tick of the walk to open sky or of the flight. */
    fun tick(): Outcome {
        if (flownAt == level.gameTime) return if (airborne || launchWalk != null) Outcome.RUNNING else Outcome.ENDED
        flownAt = level.gameTime
        val id = droneId ?: return tickPreparation()
        val drone = level.getEntity(id)?.takeIf(Ports.drones::isPiloted)
        if (drone == null || !Ports.vehicles.isOperational(drone) || Ports.vehicles.healthFraction(drone) <= 0f) {
            // Shot down. Addon drone: its destroy() already blew up. Bare SBW drone: SBW's own
            // kamikaze blast needs a player controller (see class doc), so it's on us.
            if (!detonated && !usesAddonBlast) lastDronePos?.let { detonate(drone, it) }
            cleanup()
            return Outcome.ENDED
        }
        lastDronePos = drone.position()

        val walk = launchWalk
        if (walk == null) {
            // Cover may have replaced the return path and ended the walk on an earlier tick.
            if (!operator.holdsAny(NpcActivity.COVER, NpcActivity.EVADE, NpcActivity.PEEK, NpcActivity.HEAL)) operator.navigation.stop()
        } else if (walk.returnHome(operator)) {
            launchWalk = null
        }
        operator.lookControl.setLookAt(drone.x, drone.y, drone.z)

        if (operator.tickCount - launchTick > MAX_FLIGHT_TICKS) {
            detonate(drone, drone.position())
            cleanup()
            return Outcome.ENDED
        }

        // Impact fuze: touching ground, a wall or a tree while under way is a hit (or a crash) —
        // either way the warhead goes off. Not while lifting off the ground or landing to recover.
        if (phase != Phase.LAUNCH && phase != Phase.RETURN &&
            (drone.onGround() || drone.horizontalCollision || drone.verticalCollision)
        ) {
            DebugFlags.log(LogGroup.DRONE, "{} drone impact at {}", operator.uuid, drone.position())
            detonate(drone, drone.position())
            cleanup()
            return Outcome.ENDED
        }

        refreshTarget(drone)

        when (phase) {
            Phase.LAUNCH -> tickLaunch(drone)
            Phase.CRUISE -> tickCruise(drone)
            Phase.HOLD -> tickHold(drone)
            Phase.ATTACK -> tickAttack(drone)
            Phase.RETURN -> tickReturn(drone)
        }
        return if (airborne) Outcome.RUNNING else Outcome.ENDED
    }

    private fun tickPreparation(): Outcome {
        val walk = launchWalk ?: return Outcome.ENDED
        val target = pending
        if (target == null) {
            abandonPreparation(retry = false)
            return Outcome.ENDED
        }
        return when (walk.approach(operator, level) { launchPoint(it) != null }) {
            DroneLaunchWalk.Result.WAITING -> Outcome.RUNNING

            DroneLaunchWalk.Result.FAILED -> {
                abandonPreparation(retry = true)
                Outcome.FAILED
            }

            DroneLaunchWalk.Result.READY -> {
                launch(target)
                if (droneId != null) walk.beginReturn(operator)
                Outcome.RUNNING
            }
        }
    }

    private fun abandonPreparation(retry: Boolean) {
        val walking = launchWalk != null
        cleanup()
        // No open sky within reach: he looks again in a while, not on the plan's next tick.
        if (walking && retry) nextLaunchTick = operator.tickCount + LAUNCH_SEARCH_RETRY_TICKS
    }

    /**
     * Keeps [targetPos] on the target while the drone can see it, or on where its side last saw
     * it. A target that died or turned friendly is lost: the plan picks the next one.
     *
     * It used to follow the target's live position whether anything saw it or not, so a man who
     * had gone in under the trees was hunted down through the canopy all the same.
     */
    private fun refreshTarget(drone: Entity) {
        if (phase == Phase.RETURN || lost) return
        val tid = targetEntityId ?: return // fixed point (ATTACK objective) — always valid
        val target = level.getEntity(tid) as? LivingEntity
        if (target != null && target.isAlive && SquadTeams.isHostile(operator, target)) {
            if (operator.tickCount >= nextSightCheckTick) {
                nextSightCheckTick = operator.tickCount + SIGHT_CHECK_TICKS
                if (droneSees(drone, target)) {
                    targetPos = target.position()
                    sawTargetTick = operator.tickCount
                } else {
                    followReports(tid)
                }
            }
            return
        }
        DebugFlags.log(LogGroup.DRONE, "{} lost its target {} (alive={}, hostile={})", operator.uuid, tid, target?.isAlive, target?.let { SquadTeams.isHostile(operator, it) })
        loseTarget()
    }

    /** Out of the drone's sight: where his side saw [tid] last, if that is newer than the drone's own look. */
    private fun followReports(tid: UUID) {
        val seen = SquadTeams.factionOf(operator)?.let { TeamAwareness.lastSighting(it, tid, level.gameTime) } ?: return
        val age = (level.gameTime - seen.tick).toInt()
        if (operator.tickCount - age > sawTargetTick) {
            targetPos = seen.pos
            sawTargetTick = operator.tickCount - age
        }
    }

    /** The plan's target is no good any more: hold over it while the plan picks the next one. */
    private fun loseTarget() {
        lost = true
        if (phase != Phase.LAUNCH) phase = Phase.HOLD
    }

    /** The drone's camera: a clear line from the drone to the target's head, within sight range.
     *  Leaves stop it, as they do a man's eyes. */
    private fun droneSees(drone: Entity, target: Entity): Boolean {
        val from = drone.boundingBox.center
        val to = target.eyePosition
        if (from.distanceToSqr(to) > DRONE_SIGHT_RANGE * DRONE_SIGHT_RANGE) return false
        return DetectionSightline.visible(level, from, to, drone)
    }

    /** Whether the drone may commit to the dive: a fixed point always, a man only while he is
     *  seen. Over a spot he's no longer at, the drone holds and looks for him instead. */
    private fun targetInSight(): Boolean = targetEntityId == null || operator.tickCount - sawTargetTick <= SIGHT_GRACE_TICKS

    private fun apply(drone: Entity, cmd: DroneFlightController.Command) {
        Ports.drones.setInputs(drone, cmd.forward, cmd.back, cmd.up, cmd.down)
        drone.yRot = cmd.yaw
    }

    private fun groundY(x: Double, z: Double): Int = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z))

    /** Heightmap samples under the drone and along the next [LOOKAHEAD_DISTANCES] blocks toward
     *  [toward] — cheap (no block reads), and MOTION_BLOCKING includes trees/roofs. */
    private fun terrainAhead(drone: Entity, toward: Vec3): List<Int> {
        val pos = drone.position()
        val dir = Vec3(toward.x - pos.x, 0.0, toward.z - pos.z).let { if (it.lengthSqr() < 1e-6) it else it.normalize() }
        val samples = ArrayList<Int>(LOOKAHEAD_DISTANCES.size + 1)
        samples += groundY(pos.x, pos.z)
        for (d in LOOKAHEAD_DISTANCES) samples += groundY(pos.x + dir.x * d, pos.z + dir.z * d)
        return samples
    }

    private fun tickLaunch(drone: Entity) {
        val desiredY = groundY(drone.x, drone.z) + clearance
        val cmd = DroneFlightController.steer(drone.position(), drone.yRot, 0.0, targetPos, desiredY, 0.0)
        // Climb straight up first — no thrust until at altitude, only the turn toward the target.
        apply(drone, DroneFlightController.Command(false, false, cmd.up, false, cmd.yaw))
        if (drone.y >= desiredY - 1.0) phase = if (lost) Phase.HOLD else Phase.CRUISE
    }

    private fun horizontalDistance(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    private fun tickCruise(drone: Entity) {
        val desiredY = DroneFlightController.cruiseAltitude(terrainAhead(drone, targetPos), clearance, drone.y)
        apply(drone, DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), targetPos, desiredY, CRUISE_SPEED))
        if (horizontalDistance(drone.position(), targetPos) <= ATTACK_RANGE) {
            if (blastClear() && targetInSight()) {
                phase = Phase.ATTACK
            } else {
                phase = Phase.HOLD
                holdUntilTick = operator.tickCount + HOLD_MAX_TICKS
            }
        }
    }

    private fun blastClear(): Boolean = FriendlyFireGuard.hasClearBlastRadius(operator, targetPos, warheadRadius())

    /** Allies inside the blast radius, or the target out of sight: loiter at altitude above it until
     *  that clears or the hold times out; then the target is given up and the plan picks another. */
    private fun tickHold(drone: Entity) {
        val desiredY = DroneFlightController.cruiseAltitude(terrainAhead(drone, targetPos), clearance, drone.y)
        val dist = horizontalDistance(drone.position(), targetPos)
        val cmd = DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), targetPos, desiredY, HOLD_SPEED)
        // Hover once overhead instead of overshooting back and forth.
        apply(drone, if (dist < HOLD_HOVER_RADIUS) DroneFlightController.Command(false, cmd.back, cmd.up, cmd.down, cmd.yaw) else cmd)
        if (lost) return
        val clear = blastClear()
        if (clear && targetInSight()) {
            phase = Phase.ATTACK
        } else if (operator.tickCount >= holdUntilTick) {
            if (clear) {
                DebugFlags.log(LogGroup.FIRE_SUPPORT, "drone {} lost sight of {} — looking elsewhere", operator.uuid, targetEntityId)
            } else {
                val blocker = FriendlyFireGuard.allyInBlast(operator, targetPos, warheadRadius())
                DebugFlags.log(LogGroup.DRONE, "{} gave up on {}: {} ({}) is {} blocks from it", operator.uuid, targetEntityId, blocker?.uuid, blocker?.let(::describe), blocker?.position()?.distanceTo(targetPos))
            }
            targetEntityId?.let { rejected += it }
            loseTarget()
        }
    }

    /** What an ally in the blast actually is — the log line that says why a drone held off. */
    private fun describe(e: LivingEntity): String =
        when (e) {
            is NpcEntity -> "${e.npcClass} ${SquadTeams.factionOf(e)}"

            is net.minecraft.world.entity.player.Player ->
                "player ${if (e.isCreative) "creative" else "survival"} faction=${SquadTeams.factionOf(e)}"

            else -> e.type.descriptionId
        }

    private fun tickAttack(drone: Entity) {
        val aim = targetPos.add(0.0, 1.0, 0.0)
        if (drone.position().distanceTo(aim) <= DETONATE_RANGE) {
            detonate(drone, drone.position())
            cleanup()
            return
        }
        // Overshot and pulling away: climb back out and come around again.
        if (horizontalDistance(drone.position(), targetPos) > ATTACK_RANGE * 1.5) {
            phase = Phase.CRUISE
            return
        }
        apply(drone, DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), aim, aim.y, DIVE_SPEED))
    }

    /**
     * Flying home. It used to arrive at full cruise speed and full cruise height and only start
     * descending inside the 4-block recovery circle — which it crossed in a few ticks, climbed
     * back to cruise height outside of, turned around and crossed again. It circled the operator
     * like that until the battery ran out and it came down on its own crew.
     *
     * So now it slows with distance, starts down well before it's overhead, and — if it still
     * can't settle (a roof or a tree over the operator) — is picked up anyway after a while.
     * Something new to go for on the way back is the plan's: it turns the drone with [aim].
     */
    private fun tickReturn(drone: Entity) {
        val home = operator.position()
        val dist = horizontalDistance(drone.position(), home)
        if (dist <= RECOVER_RADIUS && drone.y - home.y <= RECOVER_HEIGHT) {
            recover(drone)
            return
        }
        if (dist <= APPROACH_RADIUS) {
            val since = approachSinceTick ?: operator.tickCount.also { approachSinceTick = it }
            if (operator.tickCount - since > APPROACH_MAX_TICKS) {
                DebugFlags.log(LogGroup.DRONE, "{} drone couldn't settle, picked up anyway", operator.uuid)
                recover(drone)
                return
            }
        }
        val desiredY = if (dist <= APPROACH_RADIUS) {
            maxOf(home.y + 1.0, terrainBefore(drone, home, dist) + 2.0)
        } else {
            DroneFlightController.cruiseAltitude(terrainAhead(drone, home), clearance, drone.y)
        }
        val speed = (dist * ARRIVAL_SPEED_PER_BLOCK).coerceIn(MIN_ARRIVAL_SPEED, CRUISE_SPEED)
        val cmd = DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), home, desiredY, speed)
        apply(drone, if (dist <= RECOVER_RADIUS) DroneFlightController.Command(false, cmd.back, cmd.up, cmd.down, cmd.yaw) else cmd)
    }

    /** Highest terrain between the drone and home, short of home itself — what the descent has to
     *  clear. Home's own column is left out: a tree over the operator shouldn't hold the drone at
     *  treetop height when the recovery safety net can pick it up there anyway. */
    private fun terrainBefore(drone: Entity, home: Vec3, dist: Double): Int {
        val pos = drone.position()
        var highest = groundY(pos.x, pos.z)
        if (dist < 1e-3) return highest
        val dx = (home.x - pos.x) / dist
        val dz = (home.z - pos.z) / dist
        var d = 2.0
        while (d < dist - RECOVER_RADIUS) {
            highest = maxOf(highest, groundY(pos.x + dx * d, pos.z + dz * d))
            d += 2.0
        }
        return highest
    }

    // --- warhead ---

    private fun warheadRadius(): Double = Ports.drones.warheadRadius(usesAddonBlast)

    /** Addon drone: its own crash explosion. Bare SBW drone: our warhead, attacker = the operator. */
    private fun detonate(drone: Entity?, at: Vec3) {
        if (detonated) return
        detonated = true
        if (usesAddonBlast) {
            drone?.let { ExperienceAwards.blame(operator) { Ports.drones.crash(it) } }
            return
        }
        Ports.drones.explodeWarhead(level, operator, drone, at)
        drone?.discard()
    }

    companion object {
        const val MAX_DRONES = 10

        // Launch envelope: 80 blocks for a RECRUIT up to 160 for ELITE (per user request, 160 since 21.10), never
        // closer than MIN_RANGE — at that distance the SMG is the answer, not a 6-second flight.
        private const val MIN_RANGE = 20.0
        private const val MIN_MAX_RANGE = 80.0
        private const val MAX_MAX_RANGE = 160.0
        private const val SELF_DEFENSE_RANGE_SQR = 6.0 * 6.0
        private const val MAX_LOS_CHECKS = 8
        private const val SCAN_INTERVAL_TICKS = 20

        private const val LAUNCH_COOLDOWN_TICKS = 200
        private const val LAUNCH_COOLDOWN_JITTER = 100
        private const val LAUNCH_SEARCH_RETRY_TICKS = 100
        private const val MAX_FLIGHT_TICKS = 1500 // 75 s — "battery out", blast wherever it is

        // Height over the highest terrain/tree/roof along the leg — jittered per flight so two
        // drones from one squad don't fly the same line.
        private const val CRUISE_CLEARANCE_MIN = 12.0
        private const val CRUISE_CLEARANCE_MAX = 18.0

        /** The drone goes up from this far over the operator's feet, clear of his head. */
        private const val LAUNCH_HEIGHT = 2.0

        /** Spots round the operator tried when the sky right above him is closed, this far out. */
        private const val LAUNCH_SIDE_POINTS = 8
        private const val LAUNCH_SIDE_STEP = 2.0

        /** A box a little larger than SBW's drone, so a clear one leaves it room. */
        private const val LAUNCH_HALF_WIDTH = 0.5
        private const val LAUNCH_BOX_HEIGHT = 0.7
        private val LOOKAHEAD_DISTANCES = doubleArrayOf(4.0, 8.0, 12.0, 16.0)

        // Blocks per tick. SBW's drone can exceed 2/tick flat out; these keep it steerable.
        private const val CRUISE_SPEED = 0.9
        private const val HOLD_SPEED = 0.4
        private const val DIVE_SPEED = 1.2

        private const val ATTACK_RANGE = 15.0
        private const val DETONATE_RANGE = 2.5
        private const val RETARGET_RADIUS = 40.0

        /** How far the drone's camera makes out a man. */
        private const val DRONE_SIGHT_RANGE = 64.0
        private const val SIGHT_CHECK_TICKS = 4

        /** Out of sight this long, and the drone no longer dives on him. */
        private const val SIGHT_GRACE_TICKS = 20
        private const val HOLD_MAX_TICKS = 100
        private const val HOLD_HOVER_RADIUS = 6.0
        private const val RECOVER_RADIUS = 4.0
        private const val RECOVER_HEIGHT = 6.0

        /** Inside this, a returning drone descends instead of holding cruise height. */
        private const val APPROACH_RADIUS = 16.0

        /** How long a returning drone may spend near home before it's picked up where it is. */
        private const val APPROACH_MAX_TICKS = 200

        /** Returning speed falls off with distance: full cruise from ~15 blocks, a crawl at the end. */
        private const val ARRIVAL_SPEED_PER_BLOCK = 0.06
        private const val MIN_ARRIVAL_SPEED = 0.15

        /** [NpcEntity.die] hook: the operator's drone crashes the moment its operator does. */
        fun onOperatorDied(operator: NpcEntity) {
            val level = operator.level() as? ServerLevel ?: return
            val droneId = DroneLinks.unlink(operator.uuid) ?: return
            val drone = level.getEntity(droneId)?.takeIf(Ports.drones::isPiloted) ?: return
            if (Ports.drones.blowsUpOnCrash(drone)) {
                Ports.drones.crash(drone)
            } else {
                Ports.drones.explodeWarhead(level, operator, drone, drone.position())
                drone.discard()
            }
            // Loot should be the gun, not the monitor.
            if (!operator.stowedWeapon.isEmpty) {
                operator.setItemInHand(InteractionHand.MAIN_HAND, operator.stowedWeapon)
                operator.stowedWeapon = ItemStack.EMPTY
            }
        }
    }
}

/** Operator -> airborne drone, so [NpcEntity.die] can crash the drone without reaching into the
 *  plan. Transient; cleared on server stop (ServerLifecycle). */
object DroneLinks {
    private val droneByOperator = HashMap<UUID, UUID>()
    fun link(operator: UUID, drone: UUID) {
        droneByOperator[operator] = drone
    }
    fun unlink(operator: UUID): UUID? = droneByOperator.remove(operator)
    fun clearAll() = droneByOperator.clear()
}

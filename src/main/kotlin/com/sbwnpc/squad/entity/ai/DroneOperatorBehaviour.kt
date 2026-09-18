package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModEntities as SbwEntities
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import java.util.UUID

/**
 * DRONE_OPERATOR's whole job: spot a target 80-150 blocks out (by rank — same autonomous scan +
 * faction relay as [MortarOperatorBehaviour], plus the squad's ATTACK focus/objective), launch an
 * SBW kamikaze drone at it and fly it there from the server, no Monitor/player needed. Core task
 * like the mortar crew — it must keep flying regardless of Fight/Idle activity.
 *
 * The drone: with the SBW Drone Warfare addon installed, its FPV drone (`sbwdroneconfig:
 * cubed_fpv_drone`, "the drone itself is the weapon") — spawned by registry id, so the addon is
 * an optional runtime dependency, not a compile one. The addon's `DroneEntityCrashExplosionMixin`
 * explodes ANY drone on `destroy()` unless SBW's kamikaze flag is set, so we set no flag and mount
 * no payload: detonating at the target is just `destroy()`, and being shot down or crashing
 * explodes it exactly as a player-flown one would (its config, sounds, particles). Without the
 * addon: SBW's bare drone with a kamikaze attachment and our own `CustomExplosion` from the
 * attachment's datapack entry, because SBW's `kamikazeExplosion` silently does nothing without a
 * player controller — on impact and when shot down alike.
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
 * detonate within [DETONATE_RANGE] or on impact with ground/blocks) — with HOLD (circle at altitude while allies are inside the
 * blast radius) and RETURN (target gone: fly home, land, refund the drone). Shot down / timed out
 * -> blast where it is. Operator killed -> [onOperatorDied]: same, "signal lost".
 *
 * One drone per operator, [MAX_DRONES] carried, refilled at a barracks
 * (SquadManager.respawnAtBarracks). While flying the operator stands still holding the Monitor
 * (its gun is stowed in `NpcEntity.stowedWeapon`) and every other movement/combat behaviour stands
 * down via `NpcEntity.operatingDrone`; suppression doesn't interrupt the flight (the operator is
 * stationary either way, SeekCoverBehaviour may shuffle it into cover meanwhile).
 */
class DroneOperatorBehaviour : ExtendedBehaviour<NpcEntity>() {

    init {
        noTimeout()
    }

    private enum class Phase { LAUNCH, CRUISE, ATTACK, HOLD, RETURN }

    private class StrikeTarget(val entityId: UUID?, val pos: Vec3)

    private var droneId: UUID? = null
    private var phase = Phase.LAUNCH
    private var targetEntityId: UUID? = null
    private var targetPos: Vec3 = Vec3.ZERO
    private var lastDronePos: Vec3? = null
    private var launchTick = 0
    private var nextLaunchTick = 0
    private var holdUntilTick = 0
    private var clearance = CRUISE_CLEARANCE_MIN
    private var detonated = false
    private var usesAddonBlast = false

    private var nextScanTick = 0
    private var lastScanResult: StrikeTarget? = null
    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    // --- eligibility ---

    private fun baseEligible(entity: NpcEntity): Boolean =
        entity.npcClass == NpcClass.DRONE_OPERATOR && !entity.diggedIn && !entity.vehicleTransport

    private fun canLaunch(entity: NpcEntity): Boolean {
        if (entity.dronesLeft <= 0) return false
        if (entity.tickCount < nextLaunchTick) return false
        if (entity.combatLockedByCover() || entity.combatLockedByMedic()) return false
        // Someone right on top of the operator: shoot back (GunAttackBehaviour), don't launch.
        val personalThreat = entity.target?.takeIf { it.isAlive && entity.distanceToSqr(it) <= SELF_DEFENSE_RANGE_SQR }
        if (personalThreat != null) return false
        return strikeTarget(entity) != null
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { baseEligible(entity) && canLaunch(entity) }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean = baseEligible(entity) && droneId != null

    override fun start(entity: NpcEntity) {
        droneId = null
        detonated = false
        val level = entity.level() as? ServerLevel ?: return
        val target = strikeTarget(entity) ?: return
        launch(entity, level, target)
    }

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        // Still airborne (operator dug in / boarded / died): signal lost, the drone comes down hard.
        val level = entity.level() as? ServerLevel
        val drone = droneId?.let { level?.getEntity(it) as? DroneEntity }
        if (drone != null && !detonated) {
            detonate(level!!, entity, drone, drone.position())
        }
        cleanup(entity)
    }

    // --- target acquisition (mirrors MortarOperatorBehaviour.fireTarget) ---

    private fun maxRange(entity: NpcEntity): Double {
        val t = entity.npcRank.ordinal / (NpcRank.entries.size - 1).toDouble()
        return MIN_MAX_RANGE + t * (MAX_MAX_RANGE - MIN_MAX_RANGE)
    }

    private fun inLaunchRange(entity: NpcEntity, pos: Vec3): Boolean {
        val d2 = entity.position().distanceToSqr(pos)
        val max = maxRange(entity)
        return d2 >= MIN_RANGE * MIN_RANGE && d2 <= max * max
    }

    private fun strikeTarget(entity: NpcEntity): StrikeTarget? {
        val squad = entity.currentSquad()
        if (squad != null && squad.order == SquadOrder.ATTACK) {
            val level = entity.level() as? ServerLevel
            squad.focusEntity?.let { fid ->
                (level?.getEntity(fid) as? LivingEntity)?.takeIf { it.isAlive }?.let {
                    if (inLaunchRange(entity, it.position())) return StrikeTarget(it.uuid, it.position())
                }
            }
            squad.objective?.let {
                val pos = it.center
                if (inLaunchRange(entity, pos)) return StrikeTarget(null, pos)
            }
        }
        return scanForEnemy(entity)
    }

    /** Own eyes first (nearest visible hostile within rank range, reported to the faction like the
     *  mortar does), then whatever the faction has relayed. Cached for 20 ticks. */
    private fun scanForEnemy(entity: NpcEntity): StrikeTarget? {
        if (entity.tickCount < nextScanTick) return lastScanResult
        nextScanTick = entity.tickCount + SCAN_INTERVAL_TICKS
        val level = entity.level() as? ServerLevel ?: return null
        val tick = level.gameTime
        val faction = SquadTeams.factionOf(entity)
        val radius = maxRange(entity)

        val candidates = ArrayList<LivingEntity>()
        NpcRegistry.forEachWithin(level, entity.position(), radius, exclude = entity) {
            if (it.isAlive && SquadTeams.isHostile(entity, it)) candidates += it
        }
        val r2 = radius * radius
        for (player in level.players()) {
            if (player.isAlive && player.distanceToSqr(entity) <= r2 && SquadTeams.isHostile(entity, player)) candidates += player
        }
        candidates.sortBy { entity.distanceToSqr(it) }

        var spotted: LivingEntity? = null
        var losChecks = 0
        for (c in candidates) {
            if (losChecks++ >= MAX_LOS_CHECKS) break
            if (!inLaunchRange(entity, c.position())) continue
            if (!entity.sensing.hasLineOfSight(c)) continue
            if (faction != null) TeamAwareness.report(faction, c.uuid, tick)
            if (spotted == null) spotted = c
        }
        if (spotted != null) {
            lastScanResult = StrikeTarget(spotted.uuid, spotted.position())
            return lastScanResult
        }

        val relayed = faction?.let { TeamAwareness.relayedContacts(it, tick) } ?: emptyList()
        val target = relayed.asSequence()
            .mapNotNull { level.getEntity(it) as? LivingEntity }
            .filter { it.isAlive && SquadTeams.isHostile(entity, it) && inLaunchRange(entity, it.position()) }
            .minByOrNull { entity.distanceToSqr(it) }
        lastScanResult = target?.let { StrikeTarget(it.uuid, it.position()) }
        return lastScanResult
    }

    // --- launch / recover ---

    private fun launch(entity: NpcEntity, level: ServerLevel, target: StrikeTarget) {
        // Preferred: the Drone Warfare addon's FPV drone — "the drone itself is the weapon". Its
        // crash-explosion mixin fires on destroy() for any drone WITHOUT SBW's kamikaze flag, so
        // no payload and no explosion code of ours: detonating is just destroying it, and being
        // shot down / falling into water explodes it exactly like a player's would. Spawned by
        // registry id so the addon stays an optional runtime dependency. Fallback without the
        // addon: SBW's bare drone with a kamikaze attachment and our own blast (see detonate).
        val addonDrone = addonFpvDroneType()?.create(level) as? DroneEntity
        val drone = addonDrone ?: DroneEntity(SbwEntities.DRONE.get(), level)
        usesAddonBlast = addonDrone != null
        val forward = Vec3.directionFromRotation(0f, entity.yRot)
        val spawn = entity.position().add(forward.scale(1.5)).add(0.0, 0.5, 0.0)
        drone.moveTo(spawn.x, spawn.y, spawn.z, entity.yRot, 0f)
        if (!usesAddonBlast) armWarhead(drone)
        // Same scoreboard team as the operator: SBW turrets / hostile vehicles treat it as an enemy
        // vehicle, allies leave it alone.
        SquadTeams.factionOf(entity)?.let { SquadTeams.assign(drone, it) }
        level.addFreshEntity(drone)

        droneId = drone.uuid
        DroneLinks.link(entity.uuid, drone.uuid)
        targetEntityId = target.entityId
        targetPos = target.pos
        lastDronePos = drone.position()
        launchTick = entity.tickCount
        holdUntilTick = 0
        detonated = false
        clearance = CRUISE_CLEARANCE_MIN + entity.random.nextDouble() * (CRUISE_CLEARANCE_MAX - CRUISE_CLEARANCE_MIN)
        phase = Phase.LAUNCH

        entity.dronesLeft--
        entity.operatingDrone = true
        entity.navigation.stop()
        holdMonitor(entity)
        DebugFlags.log("[drone-debug] {} launched drone {} at {} ({} left)", entity.uuid, drone.uuid, target.pos, entity.dronesLeft)
    }

    /** Mirrors the player-side `DroneEntity.interact` attach branch for a kamikaze payload. */
    private fun armWarhead(drone: DroneEntity) {
        val payload = ItemStack(WARHEAD_ITEM.get())
        val data = CustomData.DRONE_ATTACHMENT[DroneEntity.getItemId(payload)] ?: return
        drone.currentItem = payload
        drone.entityData.set(DroneEntity.DISPLAY_ENTITY, data.displayEntity())
        drone.entityData.set(DroneEntity.IS_KAMIKAZE, true)
        drone.entityData.set(DroneEntity.MAX_AMMO, 1)
        drone.setAmmo(1)
        val scale = data.scale()
        val offset = data.offset()
        val rotation = data.rotation()
        drone.entityData.set(
            DroneEntity.DISPLAY_DATA, listOf(
                scale[0], scale[1], scale[2],
                offset[0], offset[1], offset[2],
                rotation[0], rotation[1], rotation[2],
                data.xLength, data.zLength,
                data.tickCount.toFloat()
            )
        )
    }

    private fun holdMonitor(entity: NpcEntity) {
        if (entity.mainHandItem.`is`(ModItems.MONITOR.get())) return
        entity.stowedWeapon = entity.mainHandItem.copy()
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ModItems.MONITOR.get()))
    }

    private fun restoreWeapon(entity: NpcEntity) {
        if (entity.stowedWeapon.isEmpty) return
        entity.setItemInHand(InteractionHand.MAIN_HAND, entity.stowedWeapon)
        entity.stowedWeapon = ItemStack.EMPTY
    }

    private fun cleanup(entity: NpcEntity) {
        if (droneId != null) {
            DroneLinks.unlink(entity.uuid)
            // Cooldown only after an actual flight — a start() that found nothing to launch at
            // shouldn't lock the operator out for the next 10-15 s.
            nextLaunchTick = entity.tickCount + LAUNCH_COOLDOWN_TICKS + entity.random.nextInt(LAUNCH_COOLDOWN_JITTER)
        }
        droneId = null
        targetEntityId = null
        lastDronePos = null
        entity.operatingDrone = false
        restoreWeapon(entity)
    }

    /** Drone back over the operator: pick it up again, no explosion. */
    private fun recover(entity: NpcEntity, drone: DroneEntity) {
        drone.discard()
        entity.dronesLeft++
        detonated = true // nothing left to blow up
        DebugFlags.log("[drone-debug] {} recovered its drone", entity.uuid)
        cleanup(entity)
    }

    // --- flight ---

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val id = droneId ?: return
        val drone = level.getEntity(id) as? DroneEntity
        if (drone == null || !drone.isAlive || drone.isWreck || drone.health <= 0f) {
            // Shot down. Addon drone: its destroy() already blew up. Bare SBW drone: SBW's own
            // kamikaze blast needs a player controller (see class doc), so it's on us.
            if (!detonated && !usesAddonBlast) lastDronePos?.let { detonate(level, entity, drone, it) }
            cleanup(entity)
            return
        }
        lastDronePos = drone.position()

        entity.navigation.stop()
        entity.lookControl.setLookAt(drone.x, drone.y, drone.z)

        if (entity.tickCount - launchTick > MAX_FLIGHT_TICKS) {
            detonate(level, entity, drone, drone.position())
            cleanup(entity)
            return
        }

        // Impact fuze: touching ground, a wall or a tree while under way is a hit (or a crash) —
        // either way the warhead goes off. Not while lifting off the ground or landing to recover.
        if (phase != Phase.LAUNCH && phase != Phase.RETURN &&
            (drone.onGround() || drone.horizontalCollision || drone.verticalCollision)
        ) {
            DebugFlags.log("[drone-debug] {} drone impact at {}", entity.uuid, drone.position())
            detonate(level, entity, drone, drone.position())
            cleanup(entity)
            return
        }

        if (!refreshTarget(entity, level, drone)) phase = Phase.RETURN

        when (phase) {
            Phase.LAUNCH -> tickLaunch(level, drone)
            Phase.CRUISE -> tickCruise(entity, level, drone)
            Phase.HOLD -> tickHold(entity, level, drone)
            Phase.ATTACK -> tickAttack(entity, level, drone)
            Phase.RETURN -> tickReturn(entity, level, drone)
        }
    }

    /** Keeps [targetPos] on the live target; re-targets around the drone if it died. False = nothing
     *  left to strike. */
    private fun refreshTarget(entity: NpcEntity, level: ServerLevel, drone: DroneEntity): Boolean {
        if (phase == Phase.RETURN) return true
        val tid = targetEntityId ?: return true // fixed point (ATTACK objective) — always valid
        val target = level.getEntity(tid) as? LivingEntity
        if (target != null && target.isAlive && SquadTeams.isHostile(entity, target)) {
            targetPos = target.position()
            return true
        }
        // Target died / stopped being hostile: anything else hostile near the drone? (The drone's
        // camera can see it — no operator line of sight required here.)
        var best: LivingEntity? = null
        var bestD2 = RETARGET_RADIUS * RETARGET_RADIUS
        NpcRegistry.forEachWithin(level, drone.position(), RETARGET_RADIUS) { npc ->
            if (npc.isAlive && SquadTeams.isHostile(entity, npc)) {
                val d2 = npc.distanceToSqr(drone)
                if (d2 < bestD2) { bestD2 = d2; best = npc }
            }
        }
        for (player in level.players()) {
            if (player.isAlive && SquadTeams.isHostile(entity, player)) {
                val d2 = player.distanceToSqr(drone)
                if (d2 < bestD2) { bestD2 = d2; best = player }
            }
        }
        val next = best ?: return false
        targetEntityId = next.uuid
        targetPos = next.position()
        if (phase == Phase.ATTACK) phase = Phase.CRUISE
        return true
    }

    private fun apply(drone: DroneEntity, cmd: DroneFlightController.Command) {
        drone.forwardInputDown = cmd.forward
        drone.backInputDown = cmd.back
        drone.upInputDown = cmd.up
        drone.downInputDown = cmd.down
        drone.leftInputDown = false
        drone.rightInputDown = false
        drone.yRot = cmd.yaw
    }

    private fun groundY(level: ServerLevel, x: Double, z: Double): Int =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())

    /** Heightmap samples under the drone and along the next [LOOKAHEAD_DISTANCES] blocks toward
     *  [toward] — cheap (no block reads), and MOTION_BLOCKING includes trees/roofs. */
    private fun terrainAhead(level: ServerLevel, drone: DroneEntity, toward: Vec3): List<Int> {
        val pos = drone.position()
        val dir = Vec3(toward.x - pos.x, 0.0, toward.z - pos.z).let { if (it.lengthSqr() < 1e-6) it else it.normalize() }
        val samples = ArrayList<Int>(LOOKAHEAD_DISTANCES.size + 1)
        samples += groundY(level, pos.x, pos.z)
        for (d in LOOKAHEAD_DISTANCES) samples += groundY(level, pos.x + dir.x * d, pos.z + dir.z * d)
        return samples
    }

    private fun tickLaunch(level: ServerLevel, drone: DroneEntity) {
        val desiredY = groundY(level, drone.x, drone.z) + clearance
        val cmd = DroneFlightController.steer(drone.position(), drone.yRot, 0.0, targetPos, desiredY, 0.0)
        // Climb straight up first — no thrust until at altitude, only the turn toward the target.
        apply(drone, DroneFlightController.Command(false, false, cmd.up, false, cmd.yaw))
        if (drone.y >= desiredY - 1.0) phase = Phase.CRUISE
    }

    private fun horizontalDistance(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    private fun tickCruise(entity: NpcEntity, level: ServerLevel, drone: DroneEntity) {
        val desiredY = DroneFlightController.cruiseAltitude(terrainAhead(level, drone, targetPos), clearance, drone.y)
        apply(drone, DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), targetPos, desiredY, CRUISE_SPEED))
        if (horizontalDistance(drone.position(), targetPos) <= ATTACK_RANGE) {
            if (blastClear(entity)) {
                phase = Phase.ATTACK
            } else {
                phase = Phase.HOLD
                holdUntilTick = entity.tickCount + HOLD_MAX_TICKS
            }
        }
    }

    private fun blastClear(entity: NpcEntity): Boolean =
        FriendlyFireGuard.hasClearBlastRadius(entity, targetPos, warheadRadius())

    /** Allies inside the blast radius: loiter at altitude above the target until they clear or the
     *  hold times out (then go home rather than blow up the squad). */
    private fun tickHold(entity: NpcEntity, level: ServerLevel, drone: DroneEntity) {
        val desiredY = DroneFlightController.cruiseAltitude(terrainAhead(level, drone, targetPos), clearance, drone.y)
        val dist = horizontalDistance(drone.position(), targetPos)
        val cmd = DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), targetPos, desiredY, HOLD_SPEED)
        // Hover once overhead instead of overshooting back and forth.
        apply(drone, if (dist < HOLD_HOVER_RADIUS) DroneFlightController.Command(false, cmd.back, cmd.up, cmd.down, cmd.yaw) else cmd)
        if (blastClear(entity)) {
            phase = Phase.ATTACK
        } else if (entity.tickCount >= holdUntilTick) {
            phase = Phase.RETURN
        }
    }

    private fun tickAttack(entity: NpcEntity, level: ServerLevel, drone: DroneEntity) {
        val aim = targetPos.add(0.0, 1.0, 0.0)
        if (drone.position().distanceTo(aim) <= DETONATE_RANGE) {
            detonate(level, entity, drone, drone.position())
            cleanup(entity)
            return
        }
        // Overshot and pulling away: climb back out and come around again.
        if (horizontalDistance(drone.position(), targetPos) > ATTACK_RANGE * 1.5) {
            phase = Phase.CRUISE
            return
        }
        apply(drone, DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), aim, aim.y, DIVE_SPEED))
    }

    private fun tickReturn(entity: NpcEntity, level: ServerLevel, drone: DroneEntity) {
        val home = entity.position()
        val dist = horizontalDistance(drone.position(), home)
        if (dist <= RECOVER_RADIUS && drone.y - home.y <= RECOVER_HEIGHT) {
            recover(entity, drone)
            return
        }
        val desiredY = if (dist <= RECOVER_RADIUS) home.y + 1.0
            else DroneFlightController.cruiseAltitude(terrainAhead(level, drone, home), clearance, drone.y)
        val cmd = DroneFlightController.steer(drone.position(), drone.yRot, drone.deltaMovement.horizontalDistance(), home, desiredY, CRUISE_SPEED)
        apply(drone, if (dist <= RECOVER_RADIUS) DroneFlightController.Command(false, cmd.back, cmd.up, cmd.down, cmd.yaw) else cmd)
    }

    // --- warhead ---

    private fun warheadRadius(): Double =
        if (usesAddonBlast) ADDON_FPV_BLAST_RADIUS
        else CustomData.DRONE_ATTACHMENT[DroneEntity.getItemId(ItemStack(WARHEAD_ITEM.get()))]?.explosionRadius?.toDouble() ?: 0.0

    /** Addon drone: destroy() -> the addon's crash explosion. Bare SBW drone: our stand-in for
     *  SBW's `kamikazeExplosion` (which needs a player controller) — same datapack-driven
     *  damage/radius, same `CustomExplosion`, attacker = the operator. */
    private fun detonate(level: ServerLevel, operator: NpcEntity, drone: DroneEntity?, at: Vec3) {
        if (detonated) return
        detonated = true
        if (usesAddonBlast) {
            Companion.crashAddonDrone(drone)
            return
        }
        Companion.explodeWarhead(level, operator, drone, at)
        drone?.discard()
    }

    companion object {
        const val MAX_DRONES = 10

        // Launch envelope: 80 blocks for a RECRUIT up to 150 for ELITE (per user request), never
        // closer than MIN_RANGE — at that distance the SMG is the answer, not a 6-second flight.
        private const val MIN_RANGE = 20.0
        private const val MIN_MAX_RANGE = 80.0
        private const val MAX_MAX_RANGE = 150.0
        private const val SELF_DEFENSE_RANGE_SQR = 6.0 * 6.0
        private const val MAX_LOS_CHECKS = 8
        private const val SCAN_INTERVAL_TICKS = 20
        private const val START_CHECK_INTERVAL_TICKS = 5

        private const val LAUNCH_COOLDOWN_TICKS = 200
        private const val LAUNCH_COOLDOWN_JITTER = 100
        private const val MAX_FLIGHT_TICKS = 1500 // 75 s — "battery out", blast wherever it is

        // Height over the highest terrain/tree/roof along the leg — jittered per flight so two
        // drones from one squad don't fly the same line.
        private const val CRUISE_CLEARANCE_MIN = 12.0
        private const val CRUISE_CLEARANCE_MAX = 18.0
        private val LOOKAHEAD_DISTANCES = doubleArrayOf(4.0, 8.0, 12.0, 16.0)

        // Blocks per tick. SBW's drone can exceed 2/tick flat out; these keep it steerable.
        private const val CRUISE_SPEED = 0.9
        private const val HOLD_SPEED = 0.4
        private const val DIVE_SPEED = 1.2

        private const val ATTACK_RANGE = 15.0
        private const val DETONATE_RANGE = 2.5
        private const val RETARGET_RADIUS = 40.0
        private const val HOLD_MAX_TICKS = 100
        private const val HOLD_HOVER_RADIUS = 6.0
        private const val RECOVER_RADIUS = 4.0
        private const val RECOVER_HEIGHT = 6.0

        /** Fallback warhead (no addon) — any SBW `drone_attachments` entry with IsKamikaze.
         *  RPG TBG: 150 dmg / r 11. */
        private val WARHEAD_ITEM = ModItems.RPG_ROCKET_TBG

        /** Drone Warfare addon's FPV drone ("cubed_fpv_drone"). A DroneEntity subclass, so
         *  everything above flies it unchanged. Null when the addon isn't installed. */
        private val ADDON_FPV_DRONE_ID = ResourceLocation.fromNamespaceAndPath("sbwdroneconfig", "cubed_fpv_drone")
        // DroneCrashExplosionSystem.FPV_CRASH_EXPLOSION_POWER = 5.8 (vanilla explosion power;
        // damage reaches ~2x that) — only used for the "allies in the blast" hold check.
        private const val ADDON_FPV_BLAST_RADIUS = 12.0

        private fun addonFpvDroneType(): EntityType<*>? =
            BuiltInRegistries.ENTITY_TYPE.getOptional(ADDON_FPV_DRONE_ID).orElse(null)

        private fun isAddonDrone(drone: DroneEntity): Boolean = drone.type !== SbwEntities.DRONE.get()

        /** destroy() is what the addon's crash-explosion mixin hooks; it discards the entity itself. */
        private fun crashAddonDrone(drone: DroneEntity?) {
            if (drone == null || !drone.isAlive) return
            drone.isWreck = true
            drone.destroy()
        }

        /** [NpcEntity.die] hook: the operator's drone crashes the moment its operator does. */
        fun onOperatorDied(operator: NpcEntity) {
            val level = operator.level() as? ServerLevel ?: return
            val droneId = DroneLinks.unlink(operator.uuid) ?: return
            val drone = level.getEntity(droneId) as? DroneEntity ?: return
            if (isAddonDrone(drone)) {
                crashAddonDrone(drone)
            } else {
                explodeWarhead(level, operator, drone, drone.position())
                drone.discard()
            }
            // Loot should be the gun, not the monitor.
            if (!operator.stowedWeapon.isEmpty) {
                operator.setItemInHand(InteractionHand.MAIN_HAND, operator.stowedWeapon)
                operator.stowedWeapon = ItemStack.EMPTY
            }
        }

        private fun explodeWarhead(level: ServerLevel, operator: NpcEntity, drone: DroneEntity?, at: Vec3) {
            val payload = drone?.currentItem?.takeIf { !it.isEmpty } ?: ItemStack(WARHEAD_ITEM.get())
            val data = CustomData.DRONE_ATTACHMENT[DroneEntity.getItemId(payload)] ?: return
            val bomb = EntityType.byString(data.displayEntity()).map { it.create(level) }.orElse(null)
            val direct = drone ?: operator
            CustomExplosion.Builder(direct)
                .source(bomb ?: direct)
                .attacker(operator)
                .damage(data.explosionDamage)
                .radius(data.explosionRadius)
                .position(at)
                .explode()
            DebugFlags.log("[drone-debug] {} warhead detonated at {}", operator.uuid, at)
        }
    }
}

/** Operator -> airborne drone, so [NpcEntity.die] can crash the drone without reaching into the
 *  brain. Transient; cleared on server stop (ServerLifecycle). */
object DroneLinks {
    private val droneByOperator = HashMap<UUID, UUID>()
    fun link(operator: UUID, drone: UUID) { droneByOperator[operator] = drone }
    fun unlink(operator: UUID): UUID? = droneByOperator.remove(operator)
    fun clearAll() = droneByOperator.clear()
}

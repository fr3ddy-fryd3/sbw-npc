package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Mobility
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.vehicle.TreeAvoidance
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.DriverAllegiance
import com.sbwnpc.squad.vehicle.VehiclePower
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * Squad-transit-by-vehicle: when a squad's objective is far enough away that walking is silly, NPCs
 * seek out nearby vehicles instead of hoofing it. NPCs never fight while mounted; hostile fire only
 * interrupts non-ATTACK transport after a controlled stop.
 *
 * Squad-level coordination is fully decentralized: each member independently consults
 * [VehicleTransportClaims], which is why this scales to several vehicles per squad without any
 * central dispatcher — a member first tries to join a vehicle a squadmate has already claimed with a
 * free seat; only if none exists does it claim a fresh vehicle as driver. Later members naturally
 * spill into a second/third vehicle once earlier ones fill up.
 *
 * Per-NPC phases:
 *  - SEEKING: not yet claimed a vehicle; scanning for one to join or claim.
 *  - BOARDING: walking to the claimed vehicle, about to mount.
 *  - WAITING_FOR_SQUAD (driver only): mounted, holding position until the rest of the squad has
 *    either boarded (any vehicle) or is otherwise occupied (fighting), or a timeout passes.
 *  - DRIVING (driver only): follows a route computed with the driver's OWN [entity.navigation]
 *    (the same A* pathfinder a walking NPC already uses, reused rather than hand-rolling raycasts —
 *    cheaper and already routes around terrain/buildings/water), recomputed periodically. That path
 *    is sized for a walking mob, not a vehicle's footprint/turning radius, so it's a guide, not a
 *    guarantee: a blind reverse-and-turn recovery still kicks in if progress stalls anyway (a tight
 *    gap the vehicle can't fit, a rock the route steps over that the vehicle can't climb...), and the
 *    driver gives up and dismounts to walk if that doesn't clear it within a hard cap either.
 *  - RIDING (passenger only): just waiting for the vehicle to arrive; dismounts itself once close
 *    enough, independently of the driver.
 *  - COMBAT_DISMOUNT: stops after hostile fire under a non-ATTACK order; the assigned gunner stays
 *    in an armed seat until the threat clears.
 */
class VehicleTransportBehaviour : ExtendedBehaviour<NpcEntity>() {

    // Indefinite by design, same reasoning as every other long-running behaviour in this codebase
    // (see SeekCoverBehaviour's doc comment for the full ExtendedBehaviour-timeout story).
    init {
        noTimeout()
    }

    private enum class Phase { SEEKING, BOARDING, WAITING_FOR_SQUAD, DRIVING, HOLDING, RIDING, COMBAT_DISMOUNT }

    private data class VehicleChoice(val vehicle: Entity, val isDriver: Boolean)

    private var phase = Phase.SEEKING
    private var targetVehicleId: java.util.UUID? = null
    private var waitStartTick = 0
    private var boardTick = 0
    private var repathCooldown = 0
    private var nextSeekTick = 0
    private var seekingStartTick = 0
    private var giveupCooldownUntilTick = 0
    private var lastNoCandidateLogTick = 0
    private var lastEligibilityLogTick = -ELIGIBILITY_LOG_INTERVAL_TICKS

    // Route the driver follows instead of a straight line to home — see class doc comment.
    private var route: List<BlockPos> = emptyList()
    private var routeIndex = 0
    private var nextRouteTick = 0
    private var lastRouteSearchTick = Int.MIN_VALUE / 2

    // Stuck detection while DRIVING — a vehicle's own physics has no pathfinding of its own (just
    // raw input flags), and the route above is only a walking-mob-shaped guide, not a guarantee, so
    // without this a stretch the vehicle genuinely can't get through would stop it dead forever.
    private var lastStuckCheckTick = 0
    private var lastStuckCheckPos: Vec3? = null
    private var recoveryUntilTick = 0
    private var recoveryTurnLeft = false
    private var avoidancePoint: Vec3? = null
    private var nextAvoidanceTick = 0

    // Last logged turn state for steerToward — purely for change-detection in the debug log, not
    // control state (the actual steering state lives on the vehicle itself).
    private var lastLoggedRight = false
    private var lastLoggedLeft = false
    private var lastFullLogTick = 0

    // -1 = not currently waiting to arrive; set the moment waitToStopThenDismount first gets called,
    // so a timeout can eventually force a dismount even if the vehicle never fully stops.
    private var arrivalWaitStartTick = -1

    // Snapshot of entity.homeCenter() taken once at boarding (see tickBoarding), then used for every
    // driving/arrival decision for the rest of the trip. MOVE is the exception: its objective is a
    // fixed point, so a newly issued MOVE order can replace it mid-trip. entity.homeCenter() is
    // combat-aim semantics for other orders: when a squad has a
    // focusEntity set (from an ATTACK order), it resolves to that entity's LIVE, continuously-updating
    // position — fine for aiming a gun, but chasing it with a vehicle means the destination itself
    // drifts every tick, so DRIVING/RIDING lock it in once instead. Eligibility (should this NPC even
    // be seeking/using a vehicle right now) still reads the live entity.homeCenter() every tick, which
    // is correct there — only the actual driving target is locked in once committed.
    private var tripDestination: Vec3? = null
    private var observedDamageStamp = 0L

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun combatInterrupted(entity: NpcEntity) =
        entity.target != null || entity.isAlert() || entity.isSuppressed()

    /** [checkGiveup] must be false when called from [checkExtraStartConditions] (deciding whether to
     *  START a brand new episode) and true from [shouldKeepRunning] (deciding whether an ALREADY
     *  RUNNING seeking episode has taken too long) — only [shouldKeepRunning] may actually TRIGGER a
     *  giveup (setting [giveupCooldownUntilTick]); [checkExtraStartConditions] only ever reads it.
     *
     *  The giveup is an absolute-time cooldown, not an episode-scoped flag: only a standing cooldown
     *  independent of phase/episode state actually keeps the behaviour stopped for a while and hands
     *  control to SquadOrderBehaviour's walking fallback — an episode-scoped reset (cleared in
     *  `stop()`) would let `checkExtraStartConditions` see a freshly-reset timer and restart
     *  immediately. It still doesn't block a genuinely fresh distant order: cooldown only gets set by
     *  an actual giveup, never by mere elapsed idle time, so an order arriving long after the last
     *  episode ended starts with no cooldown at all. Cheap checks (combat/dug-in/cooldown) run before
     *  the squad/home/distance lookups, and the log line's reason is only formatted when actually
     *  about to be logged. */
    private fun eligible(entity: NpcEntity, checkGiveup: Boolean): Boolean {
        // Never take the controls of something that isn't a ground vehicle. A helicopter reads
        // forwardInputDown as its collective and the left/right pedals as roll, so "driving" one
        // spins the rotor up and rolls it onto its back; HelicopterPilotBehaviour owns those.
        entity.vehicle?.takeIf(Ports.vehicles::isVehicle)?.let { mounted ->
            if (Ports.vehicles.mobility(mounted) != Mobility.GROUND) {
                return logEligibility(entity, false) { "mounted in a non-ground vehicle" }
            }
        }
        // A permanent crew member is driven by this behaviour only while in its own vehicle;
        // VehicleCrewBehaviour handles getting it back aboard if it is ever ejected.
        entity.assignedVehicleId?.let { assigned -> return entity.vehicle?.uuid == assigned }
        if (entity.vehicle != null) return true // already mounted: DRIVING/RIDING keep going regardless

        if (combatInterrupted(entity)) {
            return logEligibility(entity, false) {
                "combat interrupted (target=${entity.target != null} alert=${entity.isAlert()} suppressed=${entity.isSuppressed()})"
            }
        }
        if (entity.diggedIn) return logEligibility(entity, false) { "dug in" }
        if (entity.operatingDrone) return logEligibility(entity, false) { "flying a drone" }
        if (entity.antiDroneEngaged) return logEligibility(entity, false) { "dealing with a hostile drone" }
        if (entity.tickCount < giveupCooldownUntilTick) {
            return logEligibility(entity, false) { "cooling down after a recent giveup (${giveupCooldownUntilTick - entity.tickCount} ticks left)" }
        }

        val squad = entity.currentSquad() ?: return logEligibility(entity, false) { "no squad" }
        val home = entity.homeCenter() ?: return logEligibility(entity, false) { "no home/objective" }
        if (shouldPrioritizeMortar(entity, squad.order)) {
            return logEligibility(entity, false) { "mortar duty takes priority for ATTACK" }
        }
        val dist = entity.position().distanceTo(home)
        if (dist <= TRANSPORT_DISTANCE_THRESHOLD) {
            return logEligibility(entity, false) { "home is only $dist blocks away (threshold $TRANSPORT_DISTANCE_THRESHOLD)" }
        }
        if (checkGiveup && phase == Phase.SEEKING && entity.tickCount - seekingStartTick > SEEK_GIVEUP_TICKS) {
            giveupCooldownUntilTick = entity.tickCount + GIVEUP_COOLDOWN_TICKS
            return logEligibility(entity, false) {
                "gave up seeking a vehicle after $SEEK_GIVEUP_TICKS ticks, cooling down for $GIVEUP_COOLDOWN_TICKS ticks"
            }
        }
        return logEligibility(entity, true) { "eligible, home=$home dist=$dist" }
    }

    private fun logEligibility(entity: NpcEntity, result: Boolean, reason: () -> String): Boolean {
        if (entity.tickCount - lastEligibilityLogTick >= ELIGIBILITY_LOG_INTERVAL_TICKS) {
            lastEligibilityLogTick = entity.tickCount
            DebugFlags.log("[vehicle-debug] {} eligible={} : {}", entity.uuid, result, reason())
        }
        return result
    }

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { eligible(entity, checkGiveup = false) }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean =
        if (entity.vehicle != null) true else eligible(entity, checkGiveup = true)

    override fun start(entity: NpcEntity) {
        val mounted = entity.vehicle?.takeIf(Ports.vehicles::isVehicle)
        if (mounted != null) {
            startMounted(entity, mounted)
            return
        }
        phase = Phase.SEEKING
        targetVehicleId = null
        repathCooldown = 0
        nextSeekTick = 0
        seekingStartTick = entity.tickCount
        lastStuckCheckPos = null
        recoveryUntilTick = 0
        route = emptyList()
        nextRouteTick = 0
        lastLoggedRight = false
        lastLoggedLeft = false
        lastFullLogTick = 0
        arrivalWaitStartTick = -1
        tripDestination = null
        observedDamageStamp = 0L
        entity.vehicleTransport = true
        DebugFlags.log(
            "[vehicle-debug] {} starting vehicle transport, home={} dist={}",
            entity.uuid, entity.homeCenter(), entity.homeCenter()?.let { entity.position().distanceTo(it) }
        )
    }

    /** Spawned vehicle crews begin seated, so initialise their transport state without making them
     *  search for and attempt to board the vehicle they already occupy. */
    private fun startMounted(entity: NpcEntity, vehicle: Entity) {
        targetVehicleId = vehicle.uuid
        repathCooldown = 0
        boardTick = entity.tickCount
        waitStartTick = entity.tickCount
        tripDestination = entity.homeCenter()
        observedDamageStamp = Ports.vehicles.lastHitTime(vehicle)
        lastStuckCheckTick = entity.tickCount
        lastStuckCheckPos = null
        recoveryUntilTick = 0
        route = emptyList()
        nextRouteTick = 0
        arrivalWaitStartTick = -1
        entity.vehicleTransport = true

        if (Ports.vehicles.seatOf(vehicle, entity) == 0) {
            VehicleTransportClaims.claimDriver(vehicle.uuid, entity.uuid)
            phase = Phase.WAITING_FOR_SQUAD
        } else {
            VehicleTransportClaims.claimPassenger(
                vehicle.uuid, entity.uuid, Ports.vehicles.seatCount(vehicle), vehicle.passengers.map { it.uuid }
            )
            phase = Phase.RIDING
        }
    }

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        VehicleTransportClaims.release(entity.uuid)
        entity.vehicleTransport = false
        phase = Phase.SEEKING
        targetVehicleId = null
        tripDestination = null
        // Without this, a stale seekingStartTick from a previous (long-finished) transport episode
        // survives into the next one — checkExtraStartConditions re-evaluates eligible() the very
        // next tick this NPC becomes eligible again (e.g. a fresh far-away order), sees
        // tickCount - seekingStartTick already far past SEEK_GIVEUP_TICKS from minutes ago, and
        // gives up before ever actually starting.
        seekingStartTick = entity.tickCount
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        if (repathCooldown > 0) repathCooldown--

        when (phase) {
            Phase.SEEKING -> tickSeeking(entity, level)
            Phase.BOARDING -> tickBoarding(entity, level)
            Phase.WAITING_FOR_SQUAD -> tickWaiting(entity, level)
            Phase.DRIVING -> tickDriving(entity)
            Phase.HOLDING -> tickHolding(entity)
            Phase.RIDING -> tickRiding(entity)
            Phase.COMBAT_DISMOUNT -> tickCombatDismount(entity)
        }
    }

    private fun tickSeeking(entity: NpcEntity, level: ServerLevel) {
        if (entity.tickCount < nextSeekTick) return
        nextSeekTick = entity.tickCount + SEEK_INTERVAL_TICKS

        val squad = entity.currentSquad() ?: return
        val nearby = Ports.vehicles.within(
            level,
            AABB.ofSize(entity.position(), SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2)
        )
        val choice = nearby.asSequence()
            .filter { entity.distanceToSqr(it) <= SEARCH_RADIUS * SEARCH_RADIUS }
            .filter { isUsableGroundVehicle(it, entity) }
            .mapNotNull { vehicle ->
                val claimedBySquad = squad.members.any { VehicleTransportClaims.vehicleOf(it) == vehicle.uuid }
                when {
                    claimedBySquad && VehicleTransportClaims.occupiedOrClaimedSeats(
                        vehicle.uuid, vehicle.passengers.map { it.uuid }
                    ) < Ports.vehicles.seatCount(vehicle) ->
                        VehicleChoice(vehicle, isDriver = false)
                    VehicleTransportClaims.claimedSeats(vehicle.uuid) == 0 && vehicle.passengers.isEmpty() ->
                        VehicleChoice(vehicle, isDriver = true)
                    else -> null
                }
            }
            .minWithOrNull(
                compareByDescending<VehicleChoice> { Ports.vehicles.seatCount(it.vehicle) }
                    .thenBy { entity.distanceToSqr(it.vehicle) }
            )

        if (choice == null) {
            if (entity.tickCount - lastNoCandidateLogTick > NO_CANDIDATE_LOG_INTERVAL_TICKS) {
                lastNoCandidateLogTick = entity.tickCount
                DebugFlags.log(
                    "[vehicle-debug] {} found no claimable vehicle within {} blocks ({} vehicles total nearby)",
                    entity.uuid, SEARCH_RADIUS, nearby.size
                )
            }
            return
        }

        val claimed = if (choice.isDriver) {
            VehicleTransportClaims.claimDriver(choice.vehicle.uuid, entity.uuid)
        } else {
            VehicleTransportClaims.claimPassenger(
                choice.vehicle.uuid, entity.uuid, Ports.vehicles.seatCount(choice.vehicle),
                choice.vehicle.passengers.map { it.uuid }
            )
        }
        if (claimed) {
            targetVehicleId = choice.vehicle.uuid
            phase = Phase.BOARDING
            DebugFlags.log(
                "[vehicle-debug] {} claimed {} seat of {} (capacity={})",
                entity.uuid, if (choice.isDriver) "driver" else "passenger", choice.vehicle.uuid,
                Ports.vehicles.seatCount(choice.vehicle)
            )
        }
    }

    private fun tickBoarding(entity: NpcEntity, level: ServerLevel) {
        val vehicle = targetVehicleId?.let { level.getEntity(it)?.takeIf(Ports.vehicles::isVehicle) }
        // Re-verified here, not just at claim time in tickSeeking — a player can board/park in the
        // vehicle during the walk over, which the app-level claim registry has no way to see.
        if (vehicle == null || !Ports.vehicles.isOperational(vehicle) || Ports.vehicles.isLocked(vehicle) ||
            hasBlockingPlayerAboard(vehicle, entity) || vehicle.passengers.size >= Ports.vehicles.seatCount(vehicle)
        ) {
            VehicleTransportClaims.release(entity.uuid)
            targetVehicleId = null
            phase = Phase.SEEKING
            seekingStartTick = entity.tickCount
            return
        }

        // Distance to the vehicle's actual hull, not its origin — a multi-block vehicle's origin can
        // sit well over BOARD_DISTANCE away from anywhere an NPC can actually stand next to it, which
        // left NPCs permanently stuck just outside boarding range, right next to a vehicle they could
        // never get "close enough" to by this check's own (wrong) measure.
        if (vehicle.boundingBox.distanceToSqr(entity.position()) > BOARD_DISTANCE * BOARD_DISTANCE) {
            if (repathCooldown == 0) {
                val speed = if (entity.currentSquad()?.order == SquadOrder.MOVE) {
                    SquadOrderBehaviour.WALK_SPEED_MODIFIER
                } else {
                    RUN_SPEED_MODIFIER
                }
                entity.navigation.moveTo(vehicle.x, vehicle.y, vehicle.z, speed)
                repathCooldown = REPATH_INTERVAL_TICKS
            }
            return
        }

        entity.navigation.stop()
        // Not force=true: lets VehicleEntity.canAddPassenger's own real seat-capacity check apply as
        // a final backstop, instead of only trusting the app-level claim registry.
        if (!entity.startRiding(vehicle, false)) {
            DebugFlags.log(
                "[vehicle-debug] {} in range of {} but startRiding refused", entity.uuid, vehicle.uuid
            )
            VehicleTransportClaims.release(entity.uuid)
            targetVehicleId = null
            phase = Phase.SEEKING
            seekingStartTick = entity.tickCount
            return
        }
        DebugFlags.log("[vehicle-debug] {} boarded {}", entity.uuid, vehicle.uuid)

        entity.currentSquad()?.faction?.let { SquadTeams.assign(vehicle, it) }

        // Snapshot the destination HERE, once, rather than letting DRIVING/RIDING re-read
        // entity.homeCenter() live every tick — see the class doc comment on tripDestination's field.
        // If it's already gone (squad disbanded, order changed mid-walk-over) there's nothing
        // to drive to; stay mounted but idle rather than steering at a stale/absent target — the
        // eligibility check will unmount this NPC on its own on the next tick.
        tripDestination = entity.homeCenter()
        observedDamageStamp = Ports.vehicles.lastHitTime(vehicle)

        boardTick = entity.tickCount
        // Reset stuck-detection state — it must not carry over from a previous vehicle (e.g. after
        // an abort-and-reseek cycle), which would compare the new vehicle's position against a
        // stale, unrelated one and could misfire a recovery maneuver immediately after boarding.
        lastStuckCheckTick = entity.tickCount
        lastStuckCheckPos = null
        recoveryUntilTick = 0
        route = emptyList()
        nextRouteTick = 0
        phase = if (VehicleTransportClaims.driverOf(vehicle.uuid) == entity.uuid) {
            waitStartTick = entity.tickCount
            Phase.WAITING_FOR_SQUAD
        } else {
            Phase.RIDING
        }
    }

    /** Vehicle can vanish out from under any mounted phase (destroyed, despawned) — that ejects the
     *  NPC (`entity.vehicle` goes back to null) without killing it, so without this the phase would
     *  never advance again: `eligible()`'s `entity.vehicle != null` fast path stops applying, but
     *  nothing else ever calls `stop()` either, permanently stranding vehicleTransport=true (every
     *  other behaviour stays locked out forever). Resetting to SEEKING lets the next tick's normal
     *  eligibility check decide whether to look for another vehicle or stand down entirely. */
    private fun mountOrAbort(entity: NpcEntity): Entity? {
        val vehicle = entity.vehicle?.takeIf(Ports.vehicles::isVehicle)
        if (vehicle == null) {
            VehicleTransportClaims.release(entity.uuid)
            targetVehicleId = null
            phase = Phase.SEEKING
            seekingStartTick = entity.tickCount
        }
        return vehicle
    }

    /** Driver only: hold position until every squadmate is either aboard some vehicle or otherwise
     *  occupied (fighting), or the wait has simply run too long. */
    private fun tickWaiting(entity: NpcEntity, level: ServerLevel) {
        val vehicle = mountOrAbort(entity) ?: return
        if (reactToVehicleFire(entity, vehicle) || engageVehicleThreat(entity, vehicle)) {
            tickCombatDismount(entity, vehicle)
            return
        }
        stopVehicle(vehicle)

        // A deliberately spawned crew holds its vehicle until it receives an objective instead of
        // immediately dismounting from the parked T-90.
        if (resolveTripDestination(entity) == null) return

        val squad = entity.currentSquad()
        val allAccountedFor = squad == null || squad.members.all { id ->
            if (id == entity.uuid) return@all true
            val member = level.getEntity(id) as? NpcEntity ?: return@all true // dead/unloaded: don't block on it
            !member.isAlive || member.vehicle != null || member.target != null || member.isAlert()
        }
        val timedOut = entity.tickCount - waitStartTick > WAIT_TIMEOUT_TICKS
        if (allAccountedFor || timedOut) {
            // Stuck-detection's baseline (lastStuckCheckTick/lastStuckCheckPos) was last set back at
            // boarding time, in tickBoarding — it was never touched during the wait. The vehicle has
            // been sitting dead still for the whole WAITING_FOR_SQUAD stretch (up to WAIT_TIMEOUT_TICKS
            // = 20s), so on DRIVING's very first stuck-check tick, "haven't moved since lastStuckCheckPos"
            // is trivially true even though nothing was ever actually stuck — it was deliberately
            // parked. That fired an immediate bogus recovery (blind reverse + turn) right at the
            // waiting spot, and since the vehicle barely moves during a single recovery burst, the next
            // check could refire the same way — this, not the pathfinding range, is what was actually
            // showing up in-game as the vehicle spinning in tight circles right where the squad boarded.
            lastStuckCheckTick = entity.tickCount
            lastStuckCheckPos = null
            recoveryUntilTick = 0
            phase = Phase.DRIVING
        }
    }

    private fun tickDriving(entity: NpcEntity) {
        val vehicle = mountOrAbort(entity) ?: return
        if (reactToVehicleFire(entity, vehicle) || engageVehicleThreat(entity, vehicle)) {
            tickCombatDismount(entity, vehicle)
            return
        }
        // Ran the battery down on the way. Nothing here can move it, so walk the rest — a
        // permanent crew stays with its vehicle as usual and just holds.
        if (!VehiclePower.hasReserve(vehicle, STRANDED_RESERVE_TICKS)) {
            stopVehicle(vehicle)
            if (isPermanentCrew(entity, vehicle)) {
                holdVehicle(vehicle)
                phase = Phase.HOLDING
            } else {
                waitToStopThenDismount(entity, vehicle, isDriver = true)
            }
            return
        }
        val home = resolveTripDestination(entity)
        if (home == null) {
            waitToStopThenDismount(entity, vehicle, isDriver = true)
            return
        }

        if (vehicle.position().distanceTo(home) <= ARRIVAL_RADIUS) {
            if (isPermanentCrew(entity, vehicle)) {
                holdVehicle(vehicle)
                phase = Phase.HOLDING
                return
            }
            waitToStopThenDismount(entity, vehicle, isDriver = true)
            return
        }

        // Hard cap: recovery below isn't guaranteed to work (e.g. genuinely boxed in) — give up and
        // let the driver walk the rest rather than sit there forever retrying.
        if (entity.tickCount - boardTick > MAX_TRANSIT_TICKS) {
            if (isPermanentCrew(entity, vehicle)) {
                holdVehicle(vehicle)
                phase = Phase.HOLDING
                return
            }
            waitToStopThenDismount(entity, vehicle, isDriver = true)
            return
        }

        if (entity.tickCount < recoveryUntilTick) {
            performRecovery(entity, vehicle)
            return
        }

        if (entity.tickCount - lastStuckCheckTick >= STUCK_CHECK_INTERVAL_TICKS) {
            val last = lastStuckCheckPos
            lastStuckCheckTick = entity.tickCount
            lastStuckCheckPos = vehicle.position()
            if (last != null && vehicle.position().distanceToSqr(last) < STUCK_DISTANCE_SQR) {
                recoveryUntilTick = entity.tickCount + RECOVERY_TICKS
                recoveryTurnLeft = entity.random.nextBoolean()
                nextRouteTick = entity.tickCount // force a fresh route once recovery ends
                performRecovery(entity, vehicle)
                return
            }
        }

        if (alliedNpcBlocksTravel(entity, vehicle, forwardDirection(vehicle))) {
            stopVehicle(vehicle)
            Ports.vehicles.cutPower(vehicle)
            nextRouteTick = entity.tickCount
            return
        }
        steerToward(vehicle, aroundTrees(entity, vehicle, currentWaypoint(entity, home)))
    }

    /** [TreeAvoidance] is a few hundred block lookups, so its answer is kept for a few ticks. */
    private fun aroundTrees(entity: NpcEntity, vehicle: Entity, waypoint: Vec3): Vec3 {
        if (entity.tickCount >= nextAvoidanceTick || avoidancePoint == null) {
            nextAvoidanceTick = entity.tickCount + AVOIDANCE_INTERVAL_TICKS
            val point = TreeAvoidance.steerPoint(entity.level(), vehicle, waypoint)
            avoidancePoint = if (point == waypoint) null else point
            if (avoidancePoint != null) DebugFlags.log("[vehicle-debug] {} steering round a tree to {}", entity.uuid, point)
        }
        return avoidancePoint ?: waypoint
    }

    /** A permanent crew holds at the last MOVE objective. A changed MOVE objective resumes driving
     *  without ejecting the driver. */
    private fun tickHolding(entity: NpcEntity) {
        val vehicle = mountOrAbort(entity) ?: return
        holdVehicle(vehicle)
        val home = resolveTripDestination(entity) ?: return
        if (vehicle.position().distanceTo(home) > ARRIVAL_RADIUS) {
            route = emptyList()
            routeIndex = 0
            nextRouteTick = entity.tickCount
            lastStuckCheckTick = entity.tickCount
            lastStuckCheckPos = null
            phase = Phase.DRIVING
        }
    }

    /** (Re)computes a route to [home] with the driver's own pathfinder, throttled to once every
     *  [ROUTE_RECOMPUTE_TICKS] (or immediately after a stuck-recovery episode, or after exhausting the
     *  current route short of home — see below — via [nextRouteTick] being force-reset). Falls back to
     *  an empty route (steer straight at [home]) if the pathfinder can't find anything, rather than
     *  getting stuck on a route that no longer exists. */
    private fun currentWaypoint(entity: NpcEntity, home: Vec3): Vec3 {
        // A failed search is still a search: retry only when due, not every tick on an empty path.
        if (entity.tickCount >= nextRouteTick) {
            lastRouteSearchTick = entity.tickCount
            nextRouteTick = entity.tickCount + ROUTE_RECOMPUTE_TICKS
            val path = entity.navigation.createPath(BlockPos.containing(home), 0)
            route = if (path != null) (0 until path.nodeCount).map { path.getNodePos(it) } else emptyList()
            routeIndex = 0
            DebugFlags.log(
                "[vehicle-debug] {} recomputed route: {} nodes, canReach={}, dist-to-home={}",
                entity.uuid, route.size, path?.canReach(), entity.position().distanceTo(home)
            )
        }
        if (route.isEmpty()) return home

        var target = route[routeIndex.coerceIn(route.indices)].center
        while (routeIndex < route.size - 1 && entity.position().distanceTo(target) < WAYPOINT_RADIUS) {
            routeIndex++
            target = route[routeIndex].center
        }

        // Vanilla pathfinding caps how far it will search at the mob's own FOLLOW_RANGE attribute
        // (confirmed in PathNavigation.createPath: search region sized to followRange+8, followRange
        // itself passed as the pathfinder's own max distance) — 72 blocks for NpcEntity, well short of
        // the 100+ block trips this behaviour triggers for. A route to a distant home is therefore
        // routinely a PARTIAL one that stops well short of the actual target. Reaching the LAST node of
        // such a route isn't arrival: without forcing an immediate recompute here, the vehicle would
        // keep steering at that same now-passed dead-end point for up to ROUTE_RECOMPUTE_TICKS (5s),
        // overshooting and turning back onto it every tick — i.e. circling in place right where the
        // route ran out. Schedule another partial route, with a minimum 10 ticks between searches
        // so a one-node path cannot create an A* retry loop.
        if (routeIndex == route.size - 1 && entity.position().distanceTo(target) < WAYPOINT_RADIUS) {
            // Degenerate/one-node partial paths must not trigger A* every tick either.
            nextRouteTick = maxOf(entity.tickCount, lastRouteSearchTick + 10)
        }
        return target
    }

    private fun performRecovery(entity: NpcEntity, vehicle: Entity) {
        if (alliedNpcBlocksTravel(entity, vehicle, forwardDirection(vehicle).scale(-1.0))) {
            stopVehicle(vehicle)
            Ports.vehicles.cutPower(vehicle)
            return
        }
        Ports.vehicles.reverse(vehicle, recoveryTurnLeft)
    }

    /** Passenger only: no driving of its own — just watches for the vehicle actually getting close
     *  enough and dismounts itself, independently of what the driver's own instance is doing. Also
     *  bails out on the same hard cap as the driver, in case the driver gave up (or died) and left
     *  the vehicle stranded with nobody driving it. */
    private fun tickRiding(entity: NpcEntity) {
        val vehicle = mountOrAbort(entity) ?: return
        if (reactToVehicleFire(entity, vehicle) || engageVehicleThreat(entity, vehicle)) {
            tickCombatDismount(entity, vehicle)
            return
        }
        val home = resolveTripDestination(entity) ?: return
        if (vehicle.position().distanceTo(home) <= ARRIVAL_RADIUS) {
            waitToStopThenDismount(entity, vehicle, isDriver = false)
            return
        }
        if (entity.tickCount - boardTick > MAX_TRANSIT_TICKS) {
            waitToStopThenDismount(entity, vehicle, isDriver = false)
        }
    }

    private fun tickCombatDismount(entity: NpcEntity, mountedVehicle: Entity? = null) {
        val vehicle = mountedVehicle ?: mountOrAbort(entity) ?: return
        if (VehicleTransportClaims.combatGunnerOf(vehicle.uuid) == entity.uuid && threatActive(entity)) {
            stopVehicle(vehicle)
            Ports.vehicles.cutPower(vehicle)
            return
        }
        waitToStopThenDismount(entity, vehicle, VehicleTransportClaims.driverOf(vehicle.uuid) == entity.uuid)
    }

    /** entity.stopRiding() drops the rider at the vehicle's CURRENT position without transferring its
     *  velocity — dismounting while still moving flings the NPC into whatever's around it. Waits for
     *  the vehicle to actually slow down before ejecting anyone (driver only cuts the throttle — a
     *  passenger doesn't control the vehicle), with a timeout fallback in case it never fully stops.
     *
     *  Releasing forwardInputDown/backInputDown alone does not slow a wheeled vehicle down fast enough:
     *  per VehicleEngineUtils.wheelEngine, `power` (throttle) only decays 3%/tick when idle and keeps
     *  adding forward thrust every tick proportional to itself regardless of input state. Zeroing
     *  `power` directly (a public synced field) removes that thrust immediately; ground friction
     *  (wheelEngine's f0, ~30-50% velocity loss/tick) then kills actual speed within a handful of ticks. */
    private fun waitToStopThenDismount(entity: NpcEntity, vehicle: Entity, isDriver: Boolean) {
        if (isPermanentCrew(entity, vehicle)) {
            holdVehicle(vehicle)
            phase = Phase.HOLDING
            return
        }
        if (isDriver) {
            stopVehicle(vehicle)
            Ports.vehicles.cutPower(vehicle)
        }
        if (arrivalWaitStartTick < 0) arrivalWaitStartTick = entity.tickCount
        val speedSqr = vehicle.deltaMovement.horizontalDistanceSqr()
        val slowEnough = speedSqr < ARRIVAL_STOP_SPEED_SQR
        val waitedTooLong = entity.tickCount - arrivalWaitStartTick > ARRIVAL_STOP_TIMEOUT_TICKS
        if (slowEnough || waitedTooLong) {
            // Diagnostic for the reported "dismounted still moving, died" — logged every time so the
            // next test tells us the actual speed/health at the exact moment of dismount instead of
            // guessing again: was it the slow-enough check firing on a real stop, or the timeout
            // firing early while still going, and was the NPC already hurt going into it.
            DebugFlags.log(
                "[vehicle-debug] {} dismounting: speed={} slowEnough={} waitedTooLong={} health={}/{} pos={}",
                entity.uuid, kotlin.math.sqrt(speedSqr), slowEnough, waitedTooLong,
                entity.health, entity.maxHealth, vehicle.position()
            )
            entity.stopRiding()
            releaseVehicleTeamIfLastAboard(vehicle, entity)
            arrivalWaitStartTick = -1
        }
    }

    private fun resolveTripDestination(entity: NpcEntity): Vec3? {
        if (entity.currentSquad()?.order != SquadOrder.MOVE) return tripDestination
        val destination = entity.homeCenter()
        if (destination != tripDestination) {
            tripDestination = destination
            route = emptyList()
            routeIndex = 0
            nextRouteTick = entity.tickCount
        }
        return tripDestination
    }

    private fun reactToVehicleFire(entity: NpcEntity, vehicle: Entity): Boolean {
        if (Ports.vehicles.lastHitTime(vehicle) <= observedDamageStamp) return false
        observedDamageStamp = Ports.vehicles.lastHitTime(vehicle)

        val attacker = Ports.vehicles.lastAttacker(vehicle) as? LivingEntity ?: return false
        if (!attacker.isAlive || !SquadTeams.isHostile(entity, attacker)) return false

        entity.rememberVehicleAttacker(attacker)
        BrainUtils.setTargetOfEntity(entity, attacker)
        if (isPermanentCrew(entity, vehicle)) return false
        if (entity.currentSquad()?.order == SquadOrder.ATTACK) return false
        return engageVehicleThreat(entity, vehicle, attacker)
    }

    private fun isPermanentCrew(entity: NpcEntity, vehicle: Entity): Boolean =
        entity.assignedVehicleId == vehicle.uuid

    private fun engageVehicleThreat(entity: NpcEntity, vehicle: Entity): Boolean {
        val threat = entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) } ?: return false
        return engageVehicleThreat(entity, vehicle, threat)
    }

    private fun engageVehicleThreat(entity: NpcEntity, vehicle: Entity, threat: LivingEntity): Boolean {
        if (entity.currentSquad()?.order == SquadOrder.ATTACK) return false

        assignCombatGunner(vehicle)?.let { gunner ->
            gunner.rememberVehicleAttacker(threat)
            BrainUtils.setTargetOfEntity(gunner, threat)
        }
        phase = Phase.COMBAT_DISMOUNT
        DebugFlags.log(
            "[vehicle-debug] {} stopping {} for combat against {}",
            entity.uuid, vehicle.uuid, threat.uuid
        )
        return true
    }

    private fun assignCombatGunner(vehicle: Entity): NpcEntity? {
        VehicleTransportClaims.combatGunnerOf(vehicle.uuid)?.let { id ->
            return vehicle.passengers.filterIsInstance<NpcEntity>().firstOrNull { it.uuid == id }
        }
        val gunner = vehicle.passengers.asSequence()
            .filterIsInstance<NpcEntity>()
            .firstOrNull { Ports.vehicles.canFire(vehicle, it) }
            ?: return null
        return gunner.takeIf { VehicleTransportClaims.claimCombatGunner(vehicle.uuid, it.uuid) }
    }

    private fun threatActive(entity: NpcEntity): Boolean =
        entity.target?.isAlive == true || entity.isAlert() || entity.isSuppressed()

    private fun shouldPrioritizeMortar(entity: NpcEntity, order: SquadOrder): Boolean {
        if (order != SquadOrder.ATTACK || entity.homeCenter() == null) return false
        val claimable: (Entity) -> Boolean = when (entity.npcClass) {
            NpcClass.MORTAR_OPERATOR -> { mortar -> !MortarClaims.isOperatorClaimedByOther(mortar.uuid, entity.uuid) }
            NpcClass.MORTAR_LOADER -> { mortar -> !MortarClaims.isLoaderClaimedByOther(mortar.uuid, entity.uuid) }
            else -> return false
        }
        val level = entity.level() as? ServerLevel ?: return false
        // Reached from eligible() every tick for mortar crew under ATTACK — cache the box query.
        if (entity.tickCount - mortarPriorityCheckTick < MORTAR_PRIORITY_CHECK_INTERVAL_TICKS) return mortarPriorityCached
        mortarPriorityCheckTick = entity.tickCount
        mortarPriorityCached = Ports.mortars.within(
            level,
            AABB.ofSize(entity.position(), MORTAR_SEARCH_RADIUS * 2, MORTAR_SEARCH_RADIUS * 2, MORTAR_SEARCH_RADIUS * 2)
        ).any {
            Ports.vehicles.isOperational(it) && entity.distanceToSqr(it) <= MORTAR_SEARCH_RADIUS * MORTAR_SEARCH_RADIUS && claimable(it)
        }
        return mortarPriorityCached
    }

    private var mortarPriorityCheckTick = Int.MIN_VALUE / 2 // not MIN_VALUE: `tickCount - MIN_VALUE` overflows
    private var mortarPriorityCached = false

    private fun isUsableGroundVehicle(vehicle: Entity, entity: NpcEntity): Boolean =
        Ports.vehicles.isOperational(vehicle) && !Ports.vehicles.isLocked(vehicle) && Ports.vehicles.seatCount(vehicle) > 0 &&
            Ports.vehicles.mobility(vehicle) == Mobility.GROUND && VehiclePower.hasReserve(vehicle) &&
            !hasBlockingPlayerAboard(vehicle, entity)

    private fun forwardDirection(vehicle: Entity): Vec3 {
        val movement = vehicle.deltaMovement
        if (movement.horizontalDistanceSqr() > 0.0025) {
            return Vec3(movement.x, 0.0, movement.z).normalize()
        }
        val view = vehicle.getViewVector(1f)
        return Vec3(view.x, 0.0, view.z).normalize()
    }

    private fun alliedNpcBlocksTravel(entity: NpcEntity, vehicle: Entity, direction: Vec3): Boolean {
        if (direction.lengthSqr() < 1.0e-6) return false
        val lookahead = maxOf(MIN_ALLY_LOOKAHEAD, vehicle.deltaMovement.horizontalDistance() * ALLY_BRAKE_LOOKAHEAD_TICKS)
        val offset = direction.normalize().scale(lookahead)
        val corridor = Ports.vehicles.hull(vehicle).expandTowards(offset.x, offset.y, offset.z).inflate(ALLY_CLEARANCE)
        val faction = SquadTeams.factionOf(entity) ?: return false
        val level = entity.level() as? ServerLevel ?: return false
        val samples = kotlin.math.ceil(lookahead / ALLY_SWEEP_STEP).toInt().coerceIn(1, MAX_ALLY_SWEEP_SAMPLES)
        // Runs every tick while driving — NpcRegistry instead of a corridor box entity query.
        NpcRegistry.forEachIn(level, corridor, exclude = entity) { ally ->
            if (ally.vehicle !== vehicle && ally.isAlive && SquadTeams.factionOf(ally) == faction &&
                (1..samples).any { step -> vehicleOverlaps(vehicle, ally, offset.scale(step.toDouble() / samples)) }
            ) return true
        }
        return false
    }

    private fun vehicleOverlaps(vehicle: Entity, entity: NpcEntity, offset: Vec3): Boolean =
        Ports.vehicles.wouldHit(vehicle, entity, offset)

    /** Throttle forward and steer at [target] — see the Vehicles adapter for how. */
    private fun steerToward(vehicle: Entity, target: Vec3) {
        val steering = Ports.vehicles.driveToward(vehicle, target)
        // Logged on every turn-state change, plus an unconditional full snapshot every
        // FULL_STATE_LOG_INTERVAL_TICKS regardless of whether anything changed — a state-change-only
        // log stays silent for an entire trip if the controller gets stuck holding steady, which is
        // exactly what hid prior bugs here (long stretches of zero log lines while something was
        // quietly wrong), so this is the one place that always shows what's actually happening.
        val dueForFullLog = vehicle.tickCount - lastFullLogTick >= FULL_STATE_LOG_INTERVAL_TICKS
        if (steering.right != lastLoggedRight || steering.left != lastLoggedLeft || dueForFullLog) {
            if (dueForFullLog) lastFullLogTick = vehicle.tickCount
            lastLoggedRight = steering.right
            lastLoggedLeft = steering.left
            DebugFlags.log(
                "[vehicle-debug] steer: {} -> right={} left={}", steering.detail, steering.right, steering.left
            )
        }
    }

    /** A player may ride with our NPC driver, but NPCs must never take over a player's vehicle. */
    private fun hasBlockingPlayerAboard(vehicle: Entity, entity: NpcEntity): Boolean {
        val players = vehicle.passengers.filterIsInstance<net.minecraft.world.entity.player.Player>()
        if (players.isEmpty()) return false
        val driver = vehicle.firstPassenger as? NpcEntity ?: return true
        val faction = SquadTeams.factionOf(entity) ?: return true
        if (SquadTeams.factionOf(driver) != faction) return true
        return players.any { !DriverAllegiance.isAlliedDriver(it, driver) }
    }

    private fun holdVehicle(vehicle: Entity) {
        stopVehicle(vehicle)
        Ports.vehicles.cutPower(vehicle)
    }

    private fun stopVehicle(vehicle: Entity) = Ports.vehicles.release(vehicle)

    companion object {
        private const val TRANSPORT_DISTANCE_THRESHOLD = 100.0
        private const val SEARCH_RADIUS = 60.0
        private const val BOARD_DISTANCE = 3.0
        private const val ARRIVAL_RADIUS = 20.0
        private const val ARRIVAL_STOP_SPEED_SQR = 0.0004 // ~0.02 blocks/tick — "stopped" next to a multi-block vehicle
        private const val ARRIVAL_STOP_TIMEOUT_TICKS = 60 // ~3s fallback if it never fully stops
        private const val WAIT_TIMEOUT_TICKS = 400 // ~20s
        private const val SEEK_INTERVAL_TICKS = 20
        private const val SEEK_GIVEUP_TICKS = 200 // ~10s of scanning before falling back to walking
        /** Looser than the reserve demanded before boarding: a trip already under way is worth
         *  finishing on the last of the battery rather than abandoning halfway. */
        private const val STRANDED_RESERVE_TICKS = 40.0
        private const val GIVEUP_COOLDOWN_TICKS = 400 // ~20s before trying again after a giveup
        private const val NO_CANDIDATE_LOG_INTERVAL_TICKS = 100
        private const val ELIGIBILITY_LOG_INTERVAL_TICKS = 60 // 3s between eligibility trace lines
        private const val REPATH_INTERVAL_TICKS = 20
        private const val FULL_STATE_LOG_INTERVAL_TICKS = 10 // unconditional steer snapshot, ~0.5s
        private const val RUN_SPEED_MODIFIER = 1.0
        private const val MORTAR_PRIORITY_CHECK_INTERVAL_TICKS = 40
        private const val START_CHECK_INTERVAL_TICKS = 5
        private const val MORTAR_SEARCH_RADIUS = 30.0
        private const val MIN_ALLY_LOOKAHEAD = 2.0
        private const val ALLY_BRAKE_LOOKAHEAD_TICKS = 3.0
        private const val ALLY_CLEARANCE = 0.3
        private const val ALLY_SWEEP_STEP = 0.5
        private const val MAX_ALLY_SWEEP_SAMPLES = 12
        private const val STUCK_CHECK_INTERVAL_TICKS = 40 // 2s between progress checks
        private const val STUCK_DISTANCE_SQR = 1.0 // moved less than 1 block in that window
        private const val RECOVERY_TICKS = 30 // ~1.5s reverse-and-turn before retrying
        private const val MAX_TRANSIT_TICKS = 2400 // ~2 min hard cap before giving up and walking
        private const val ROUTE_RECOMPUTE_TICKS = 100 // 5s between route refreshes
        // Deliberately much larger than SquadOrderBehaviour's walking-mob waypoint radius (3 blocks) —
        // a walking mob can turn on the spot, a vehicle cannot. A waypoint inside the vehicle's own
        // minimum turning radius is physically unpointable at: the vehicle just orbits that spot,
        // unable to close the heading gap no matter how hard it turns. Advancing to the next waypoint
        // well before getting that close avoids ever asking the vehicle to hit a target tighter than it
        // can physically steer around.
        private const val WAYPOINT_RADIUS = 15.0
        private const val AVOIDANCE_INTERVAL_TICKS = 5

        /** Clears the temporary squad team after the final NPC leaves or dies in the vehicle. */
        fun releaseVehicleTeamIfLastAboard(vehicle: Entity, leaving: NpcEntity) {
            if (vehicle.passengers.any { it is NpcEntity && it !== leaving }) return
            SquadTeams.clear(vehicle)
        }
    }
}

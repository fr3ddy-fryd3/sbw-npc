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
import com.sbwnpc.squad.combat.LogGroup
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.minecraft.util.Mth
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
 *  - DRIVING (driver only): follows a way over the land from the long-route planner
 *    ([com.sbwnpc.squad.route.Driving]): sized to the hull and its step height, round whatever it
 *    can't climb or fit through, and ending where the crew is better off walking on — at the goal,
 *    or at the foot of a climb. A blind reverse-and-turn recovery still kicks in if progress
 *    stalls anyway, and the way is searched again after it; the driver gives up and dismounts to
 *    walk if that doesn't clear it within a hard cap either. Where no long route is found it
 *    falls back on short routes from the driver's own pathfinder.
 *  - RIDING (passenger only): just waiting for the vehicle to arrive; dismounts itself once close
 *    enough, independently of the driver.
 *  - COMBAT_DISMOUNT: stops after hostile fire under a non-ATTACK order; the assigned gunner stays
 *    in an armed seat until the threat clears.
 *
 * Boats: [BoatTrips] weighs the boats near a squad against walking — down a river, across a lake,
 * over the water on the squad's route — and when one is worth it the squad takes it instead of a
 * road vehicle, whatever the distance. The driver follows the way over the water it found
 * ([com.sbwnpc.squad.vehicle.WaterRoutes]) to the bank nearest the objective, where everyone gets
 * off and walks on. A squad bigger than the boats' seats walks the rest; nobody boards again for a
 * while after landing, and a boat doesn't stop to fight on the water.
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
    /** The order a scan found no vehicle for — see [tickSeeking]. */
    private var noVehicleForStamp = Int.MIN_VALUE
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
    /** The heading at the last progress check: turning on the spot or backing round is progress. */
    private var lastStuckCheckYaw = 0f
    private var recoveryUntilTick = 0
    private var recoveryTurnLeft = false
    private var avoidancePoint: Vec3? = null
    private var nextAvoidanceTick = 0
    // A man of our side in the way: since when, and until when the vehicle is steering round him.
    private var allyBlockedSince = -1
    private var detourUntilTick = 0

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
    /** Last tick the combat gunner could see its target — see [gunnerStillEngaged]. */
    private var gunnerSawThreatTick = 0
    /** Boat trips worth taking to the objective, as of the last eligibility check — see [BoatTrips]. */
    private var boatTrips: List<BoatTrips.Trip> = emptyList()
    /** The way over the water the boat being driven follows, and how far along it it is. */
    private var boatRoute: List<Vec3> = emptyList()
    private var boatRouteIndex = 0
    /**
     * A vehicle's way over the land from the long-route planner ([Driving]): its turns, whether it
     * ends where the crew gets out or runs on into ground not yet seen, and the search under way
     * for the next one.
     */
    private var driveRoute: List<Vec3> = emptyList()
    private var driveRouteComplete = true
    private var driveSearch: com.sbwnpc.squad.route.CellPlanner.Search? = null
    private var driveSearchStarted = 0
    /** Not before this tick is the next stretch of a vehicle's way into unseen ground looked for again. */
    private var driveLookAheadTick = 0
    /** The long-route planner found no way from here: the vehicle keeps to the old short routes. */
    private var driveLegsOnly = false
    /** When the boat last got nearer the end of its way, and how near that was — a boat that has
     *  stopped getting anywhere is given up on, however long its voyage. */
    private var boatProgressTick = 0
    private var boatBestRemaining = Double.MAX_VALUE
    /** As fast as the boat should go for the bends ahead — see [pursue]. */
    private var boatTargetSpeed = 1.0
    /** Whether the way the boat follows ends at a bank — see [com.sbwnpc.squad.route.CellPlanner.Route.complete]. */
    private var boatRouteComplete = true
    /** Not before this tick is the next stretch of a way that goes on looked for again. */
    private var boatLookAheadTick = 0
    /** When the boat last got stuck — a second time soon after means its way is no good. */
    private var boatStuckTick = Int.MIN_VALUE / 2
    /** Since when the boat this man rides has had nobody at the wheel — see [tickRiding]. */
    private var driverlessSince = -1
    /** What the man at a boat's machine gun is firing at — see [workBoatGun]. */
    private var boatGunTarget: java.util.UUID? = null
    /** No boat trips before this — just landed, or found no boat to take. */
    private var boatCooldownUntilTick = 0

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
            val mobility = Ports.vehicles.mobility(mounted)
            if (mobility != Mobility.GROUND && mobility != Mobility.WATER) {
                return logEligibility(entity, false) { "mounted in a vehicle that isn't driven" }
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
        // Off to a Supply: the order waits till he's topped up, the ride to it too. Both ran at
        // once, and each took the other off him in turn.
        if (entity.resupplying) return logEligibility(entity, false) { "resupplying" }
        if (entity.operatingDrone) return logEligibility(entity, false) { "flying a drone" }
        if (entity.antiDroneEngaged) return logEligibility(entity, false) { "dealing with a hostile drone" }
        // Already looked once for this order and there was nothing: walk it.
        if (entity.currentSquad()?.orderStamp == noVehicleForStamp) {
            return logEligibility(entity, false) { "no vehicle for this order, walking" }
        }
        if (entity.tickCount < giveupCooldownUntilTick) {
            return logEligibility(entity, false) { "cooling down after a recent giveup (${giveupCooldownUntilTick - entity.tickCount} ticks left)" }
        }

        val squad = entity.currentSquad() ?: return logEligibility(entity, false) { "no squad" }
        // A barrage's objective is what the tube shells, not somewhere to be driven to.
        if (squad.order == SquadOrder.BARRAGE) return logEligibility(entity, false) { "barrage is fired from the tube" }
        val home = entity.homeCenter() ?: return logEligibility(entity, false) { "no home/objective" }
        if (shouldPrioritizeMortar(entity, squad.order)) {
            return logEligibility(entity, false) { "mortar duty takes priority for ATTACK" }
        }
        val trips = if (entity.tickCount < boatCooldownUntilTick) emptyList()
            else BoatTrips.tripsFor(entity, home, SquadMarch.crossingAhead(entity) != null) { isUsableBoat(it, entity) }
                ?: boatTrips.takeIf { checkGiveup && it.isNotEmpty() }
                ?: return logEligibility(entity, false) { "weighing the boats nearby" }
        // Once under way to a boat, the trip stays: the squad's decision can be looked at again
        // meanwhile and come out differently from where he now stands, and dropping it there broke
        // the trip off and freed his seat over and over.
        boatTrips = trips.ifEmpty { boatTrips.takeIf { checkGiveup && targetVehicleId != null }.orEmpty() }
        val dist = entity.position().distanceTo(home)
        if (dist <= TRANSPORT_DISTANCE_THRESHOLD && boatTrips.isEmpty()) {
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
            DebugFlags.log(LogGroup.VEHICLE, "{} eligible={} : {}", entity.uuid, result, reason())
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
        allyBlockedSince = -1
        route = emptyList()
        nextRouteTick = 0
        lastLoggedRight = false
        lastLoggedLeft = false
        lastFullLogTick = 0
        arrivalWaitStartTick = -1
        tripDestination = null
        observedDamageStamp = 0L
        entity.vehicleTransport = true
        DebugFlags.log(LogGroup.VEHICLE,
            "{} starting vehicle transport, home={} dist={}",
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
        tripDestination = if (isBoat(vehicle)) landingOf(vehicle) ?: BoatTrips.tripOf(vehicle.uuid)?.route?.landing else entity.homeCenter()
        boatRoute = emptyList()
        boatRouteComplete = true
        resetDriveRoute()
        boatProgressTick = entity.tickCount
        boatBestRemaining = Double.MAX_VALUE
        observedDamageStamp = Ports.vehicles.lastHitTime(vehicle)
        lastStuckCheckTick = entity.tickCount
        lastStuckCheckPos = null
        recoveryUntilTick = 0
        allyBlockedSince = -1
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
        boatTrips = emptyList()
        boatRoute = emptyList()
        boatRouteComplete = true
        resetDriveRoute()
        boatProgressTick = entity.tickCount
        boatBestRemaining = Double.MAX_VALUE
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
            .filter { vehicle ->
                if (boatTrips.isNotEmpty()) boatTrips.any { it.boat == vehicle.uuid } && isUsableBoat(vehicle, entity)
                else isUsableGroundVehicle(vehicle, entity)
            }
            .mapNotNull { vehicle ->
                val claimedBySquad = squad.members.any { VehicleTransportClaims.vehicleOf(it) == vehicle.uuid }
                // Whoever claimed the wheel may have dropped it since; the next man takes it, or
                // the rest board as passengers and nobody drives.
                val wheelFree = VehicleTransportClaims.driverOf(vehicle.uuid) == null &&
                    Ports.vehicles.seating(vehicle).firstOrNull() == null
                when {
                    claimedBySquad && wheelFree -> VehicleChoice(vehicle, isDriver = true)
                    claimedBySquad && VehicleTransportClaims.occupiedOrClaimedSeats(
                        vehicle.uuid, vehicle.passengers.map { it.uuid }
                    ) < Ports.vehicles.seatCount(vehicle) ->
                        VehicleChoice(vehicle, isDriver = false)
                    // Nobody's claim and no NPC aboard — a player of ours in the back rides along.
                    VehicleTransportClaims.claimedSeats(vehicle.uuid) == 0 &&
                        vehicle.passengers.none { it !is net.minecraft.world.entity.player.Player } &&
                        Ports.vehicles.seating(vehicle).firstOrNull() == null ->
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
                DebugFlags.log(LogGroup.VEHICLE,
                    "{} found no claimable vehicle within {} blocks ({} vehicles total nearby)",
                    entity.uuid, SEARCH_RADIUS, nearby.size
                )
            }
            // The boats worth taking are full or gone: walk, or a road vehicle if it's far — and
            // leave the boats be for a while.
            if (boatTrips.isNotEmpty()) {
                boatTrips = emptyList()
                boatCooldownUntilTick = entity.tickCount + BOAT_COOLDOWN_TICKS
                return
            }
            // One look is enough. Standing here scanning for ten seconds, walking for twenty and
            // scanning again was a squad on a long march freezing every half a minute. Nothing in
            // reach now: walk, and don't look again until the next order.
            noVehicleForStamp = squad.orderStamp
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
            if (isBoat(choice.vehicle) && VehicleTransportClaims.landingOf(choice.vehicle.uuid) == null) {
                boatTrips.firstOrNull { it.boat == choice.vehicle.uuid && it.route.complete }?.let {
                    VehicleTransportClaims.setLanding(choice.vehicle.uuid, it.route.landing)
                }
            }
            targetVehicleId = choice.vehicle.uuid
            phase = Phase.BOARDING
            DebugFlags.log(LogGroup.VEHICLE,
                "{} claimed {} seat of {} (capacity={})",
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
            DebugFlags.log(LogGroup.VEHICLE,
                "{} in range of {} but startRiding refused", entity.uuid, vehicle.uuid
            )
            VehicleTransportClaims.release(entity.uuid)
            targetVehicleId = null
            phase = Phase.SEEKING
            seekingStartTick = entity.tickCount
            return
        }
        DebugFlags.log(LogGroup.VEHICLE, "{} boarded {}", entity.uuid, vehicle.uuid)

        entity.currentSquad()?.faction?.let { SquadTeams.assign(vehicle, it) }

        // Snapshot the destination HERE, once, rather than letting DRIVING/RIDING re-read
        // entity.homeCenter() live every tick — see the class doc comment on tripDestination's field.
        // If it's already gone (squad disbanded, order changed mid-walk-over) there's nothing
        // to drive to; stay mounted but idle rather than steering at a stale/absent target — the
        // eligibility check will unmount this NPC on its own on the next tick.
        tripDestination = if (isBoat(vehicle)) landingOf(vehicle) ?: BoatTrips.tripOf(vehicle.uuid)?.route?.landing else entity.homeCenter()
        observedDamageStamp = Ports.vehicles.lastHitTime(vehicle)
        boatRoute = emptyList()
        boatRouteComplete = true
        resetDriveRoute()
        boatProgressTick = entity.tickCount
        boatBestRemaining = Double.MAX_VALUE

        boardTick = entity.tickCount
        // Reset stuck-detection state — it must not carry over from a previous vehicle (e.g. after
        // an abort-and-reseek cycle), which would compare the new vehicle's position against a
        // stale, unrelated one and could misfire a recovery maneuver immediately after boarding.
        lastStuckCheckTick = entity.tickCount
        lastStuckCheckPos = null
        recoveryUntilTick = 0
        allyBlockedSince = -1
        route = emptyList()
        nextRouteTick = 0
        // The vehicle seats a man in its first free seat, whatever he claimed, and whoever is in
        // seat 0 is the one at the wheel: a passenger who boarded first sat there and nobody drove.
        val atWheel = Ports.vehicles.seatOf(vehicle, entity) == 0
        if (atWheel && VehicleTransportClaims.driverOf(vehicle.uuid) != entity.uuid) {
            val claimant = VehicleTransportClaims.driverOf(vehicle.uuid)
            claimant?.let { VehicleTransportClaims.release(it) }
            VehicleTransportClaims.claimDriver(vehicle.uuid, entity.uuid)
            // Whoever had claimed the wheel still has a place, just not that one.
            claimant?.let {
                VehicleTransportClaims.claimPassenger(
                    vehicle.uuid, it, Ports.vehicles.seatCount(vehicle), vehicle.passengers.map { p -> p.uuid }
                )
            }
        } else if (!atWheel && VehicleTransportClaims.driverOf(vehicle.uuid) == entity.uuid) {
            VehicleTransportClaims.claimPassenger(
                vehicle.uuid, entity.uuid, Ports.vehicles.seatCount(vehicle), vehicle.passengers.map { it.uuid }
            )
        }
        DebugFlags.log(LogGroup.VEHICLE, "{} seated in {} as {}", entity.uuid, vehicle.uuid, if (atWheel) "driver" else "passenger")
        phase = if (atWheel) {
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
        val boat = isBoat(vehicle)
        val allAccountedFor = squad == null || squad.members.all { id ->
            if (id == entity.uuid) return@all true
            val member = level.getEntity(id) as? NpcEntity ?: return@all true // dead/unloaded: don't block on it
            !member.isAlive || member.vehicle != null || member.target != null || member.isAlert() ||
                // A boat seats few: whoever isn't coming along is swimming, not late.
                (boat && !member.vehicleTransport)
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
            allyBlockedSince = -1
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

        if (arrived(entity, vehicle, home)) {
            if (isPermanentCrew(entity, vehicle)) {
                holdVehicle(vehicle)
                phase = Phase.HOLDING
                return
            }
            waitToStopThenDismount(entity, vehicle, isDriver = true)
            return
        }

        // Hard cap: recovery below isn't guaranteed to work (e.g. genuinely boxed in) — give up and
        // let the driver walk the rest rather than sit there forever retrying. A boat's voyage can
        // run to several minutes: it gives up only once it has stopped getting anywhere.
        val givenUp = if (isBoat(vehicle)) entity.tickCount - boatProgressTick > BOAT_NO_PROGRESS_TICKS
            else entity.tickCount - boardTick > MAX_TRANSIT_TICKS
        if (givenUp) {
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
            val turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(vehicle.yRot - lastStuckCheckYaw)) > STUCK_TURN_DEGREES
            lastStuckCheckTick = entity.tickCount
            lastStuckCheckPos = vehicle.position()
            lastStuckCheckYaw = vehicle.yRot
            if (last != null && !turned && vehicle.position().distanceToSqr(last) < STUCK_DISTANCE_SQR) {
                // Run aground short of the landing point: this is the shore, get off here.
                if (isBoat(vehicle) && boatRouteComplete && horizontalDistance(vehicle.position(), home) <= BOAT_AGROUND_RADIUS) {
                    waitToStopThenDismount(entity, vehicle, isDriver = true)
                    return
                }
                // A boat takes a while to get going astern, and more with its bow in the bank.
                recoveryUntilTick = entity.tickCount + if (isBoat(vehicle)) BOAT_RECOVERY_TICKS else RECOVERY_TICKS
                recoveryTurnLeft = entity.random.nextBoolean()
                if (isBoat(vehicle)) {
                    // Backed off, a boat carries on along the way it had: searched afresh from
                    // up against a bank, it came out with a worse landing than the one it was on
                    // its way to. Only stuck again soon after is the way itself no good.
                    if (entity.tickCount - boatStuckTick < BOAT_RESTUCK_TICKS) nextRouteTick = entity.tickCount
                    boatStuckTick = entity.tickCount
                } else {
                    nextRouteTick = entity.tickCount // force a fresh route once recovery ends
                }
                performRecovery(entity, vehicle)
                return
            }
        }

        val blocker = allyInTheWay(entity, vehicle, forwardDirection(vehicle))
        if (blocker != null) {
            stopVehicle(vehicle)
            Ports.vehicles.cutPower(vehicle)
            nextRouteTick = entity.tickCount
            // Waiting on a man is not being stuck; the go-round below is what handles it.
            lastStuckCheckTick = entity.tickCount
            lastStuckCheckPos = vehicle.position()
            if (allyBlockedSince < 0) allyBlockedSince = entity.tickCount
            // He hasn't moved off — he may not be able to, pressed against the hull. Back off and
            // go round him instead of waiting out the whole trip.
            if (entity.tickCount - allyBlockedSince >= ALLY_WAIT_TICKS) {
                DebugFlags.log(LogGroup.VEHICLE, "{} blocked by {} for {} ticks, backing off to go round",
                    entity.uuid, blocker.uuid, entity.tickCount - allyBlockedSince)
                allyBlockedSince = -1
                recoveryUntilTick = entity.tickCount + RECOVERY_TICKS
                recoveryTurnLeft = entity.random.nextBoolean()
                detourUntilTick = recoveryUntilTick + DETOUR_TICKS
                avoidancePoint = null
                nextAvoidanceTick = entity.tickCount
                performRecovery(entity, vehicle)
            }
            return
        }
        allyBlockedSince = -1
        if (isBoat(vehicle)) {
            // Its own way over the water; the land route and the trees are no use there.
            val waypoint = boatWaypoint(entity, vehicle, home)
            // In to the bank at a crawl: at full speed it ploughed in wherever the bow pointed. And
            // no faster than the bends ahead allow.
            val speed = vehicle.deltaMovement.horizontalDistance()
            val coasting = (horizontalDistance(vehicle.position(), home) < BOAT_SLOW_RADIUS && speed > BOAT_APPROACH_SPEED) ||
                speed > boatTargetSpeed
            if (waypoint == null) stopVehicle(vehicle) else steerToward(vehicle, waypoint, throttle = !coasting)
            return
        }
        val waypoint = if (driveLegsOnly) currentWaypoint(entity, home) else driveWaypoint(entity, vehicle, home)
        // No way yet: the first search takes a moment. Wait for it rather than set off wrong.
        if (waypoint == null) stopVehicle(vehicle) else steerToward(vehicle, aroundTrees(entity, vehicle, waypoint))
    }

    /**
     * The next point on the boat's way over the water to [landing]. The way the squad's decision
     * found is taken as it is; after the boat has been knocked off it (a stuck recovery forces
     * [nextRouteTick]) a fresh one is searched from where it is. Null while there is none yet —
     * the boat waits for it rather than setting off at the bank.
     */
    private fun boatWaypoint(entity: NpcEntity, vehicle: Entity, landing: Vec3): Vec3? {
        if (boatRoute.isEmpty() || entity.tickCount >= nextRouteTick) {
            val trip = BoatTrips.tripOf(vehicle.uuid)
            val route = if (boatRoute.isEmpty() && trip != null && trip.route.route.isNotEmpty() &&
                trip.route.route.first().distanceTo(vehicle.position()) < BOAT_WAYPOINT_RADIUS * 2
            ) trip.route else BoatTrips.replan(vehicle, trip?.goal ?: landing)
            if (route == null) {
                if (boatRoute.isEmpty()) return null
                // At the end of a stretch that goes on and the next not found yet: wait there
                // rather than circle the last point.
                if (!boatRouteComplete && horizontalDistance(vehicle.position(), boatRoute.last()) < BOAT_WAYPOINT_RADIUS * 2) return null
            } else {
                boatRoute = route.route
                boatRouteIndex = 0
                nextRouteTick = Int.MAX_VALUE
                boatRouteComplete = route.complete
                boatBestRemaining = Double.MAX_VALUE
                boatProgressTick = entity.tickCount
                // The way can come out at another bank than the one first picked; everyone aboard
                // gets off where it actually goes — and nowhere while it goes on past what was searched.
                if (route.complete) VehicleTransportClaims.setLanding(vehicle.uuid, route.landing)
                else VehicleTransportClaims.clearLanding(vehicle.uuid)
                tripDestination = route.landing
                DebugFlags.log(LogGroup.BOAT,
                    "{} steering {} along {} points, {} blocks, {} {}",
                    entity.uuid.toString().take(8), vehicle.uuid.toString().take(8), boatRoute.size,
                    route.length.toInt(), if (route.complete) "to land at" else "on toward the goal, to look again at",
                    BlockPos.containing(route.shore)
                )
            }
        }
        // Nearing the end of a way that goes on: search the next stretch while still running
        // straight along this one. Waiting for the very end had the boat at full speed there with
        // the next stretch starting off at a right angle.
        if (!boatRouteComplete && boatRoute.isNotEmpty() && entity.tickCount >= boatLookAheadTick &&
            horizontalDistance(vehicle.position(), boatRoute.last()) < BOAT_LOOKAHEAD
        ) {
            boatLookAheadTick = entity.tickCount + BOAT_LOOKAHEAD_RETRY_TICKS
            nextRouteTick = entity.tickCount
        }
        boatRoute.lastOrNull()?.let { end ->
            val remaining = horizontalDistance(vehicle.position(), end)
            if (remaining < boatBestRemaining - BOAT_PROGRESS_STEP) {
                boatBestRemaining = remaining
                boatProgressTick = entity.tickCount
            }
        }
        return pursue(vehicle) ?: landing
    }

    /**
     * Steering along the boat's way: not at the next point of it but at a point some way ahead on
     * it — further the faster it goes — so the turn is begun before the bend, not in it; and
     * [boatTargetSpeed] from the bends coming up. A boat's turn widens with its speed: fifty blocks
     * at full speed, a dozen at a crawl. Aimed at the next point at full speed it went round in
     * circles missing it, or up the bank.
     */
    private fun pursue(vehicle: Entity): Vec3? {
        val route = boatRoute
        if (route.isEmpty()) return null
        if (route.size == 1) {
            boatTargetSpeed = BOAT_SLOW_TURN_SPEED
            return route[0]
        }
        val here = vehicle.position()
        // Where along the way the boat is: the nearest point on the next few legs.
        var leg = boatRouteIndex.coerceIn(0, route.size - 2)
        var along = 0.0
        var nearest = Double.MAX_VALUE
        for (i in leg until minOf(route.size - 1, leg + BOAT_LEGS_LOOKED_AT)) {
            val a = route[i]
            val b = route[i + 1]
            val len = horizontalDistance(a, b)
            val t = if (len < 1e-6) 0.0 else (((here.x - a.x) * (b.x - a.x) + (here.z - a.z) * (b.z - a.z)) / (len * len)).coerceIn(0.0, 1.0)
            val d = Math.hypot(a.x + (b.x - a.x) * t - here.x, a.z + (b.z - a.z) * t - here.z)
            if (d < nearest) {
                nearest = d
                leg = i
                along = t * len
            }
        }
        boatRouteIndex = leg
        val speed = vehicle.deltaMovement.horizontalDistance()

        // The point to steer at, carried on along the way from where the boat is.
        var ahead = BOAT_CARROT_MIN + speed * BOAT_CARROT_PER_SPEED
        var i = leg
        var fromStart = along
        var carrot = route.last()
        while (i < route.size - 1) {
            val len = horizontalDistance(route[i], route[i + 1])
            if (fromStart + ahead <= len) {
                val t = (fromStart + ahead) / len
                carrot = Vec3(route[i].x + (route[i + 1].x - route[i].x) * t, route[i].y, route[i].z + (route[i + 1].z - route[i].z) * t)
                break
            }
            ahead -= len - fromStart
            fromStart = 0.0
            i++
        }

        // How fast to go: slow enough for the sharpest bend within braking reach.
        var target = BOAT_FULL_SPEED
        var distance = horizontalDistance(route[leg], route[leg + 1]) - along
        val reach = BOAT_BRAKE_MIN + speed * BOAT_BRAKE_PER_SPEED
        var j = leg + 1
        while (j < route.size - 1 && distance <= reach) {
            val inX = route[j].x - route[j - 1].x
            val inZ = route[j].z - route[j - 1].z
            val outX = route[j + 1].x - route[j].x
            val outZ = route[j + 1].z - route[j].z
            val turn = Math.abs(Mth.wrapDegrees(Math.toDegrees(Math.atan2(outZ, outX) - Math.atan2(inZ, inX))))
            target = minOf(target, speedForTurn(turn))
            distance += horizontalDistance(route[j], route[j + 1])
            j++
        }
        boatTargetSpeed = target
        return carrot
    }

    /** The speed a boat can take a bend of [degrees] at without running wide into the bank. */
    private fun speedForTurn(degrees: Double): Double = when {
        degrees <= 20.0 -> BOAT_FULL_SPEED
        degrees <= 45.0 -> 0.55
        degrees <= 75.0 -> 0.4
        else -> BOAT_SLOW_TURN_SPEED
    }

    private fun horizontalDistance(a: Vec3, b: Vec3): Double = Math.hypot(a.x - b.x, a.z - b.z)

    /**
     * A boat at rest with ground to step out onto beside it: everyone gets off here, whether or not
     * it came right up to its landing point. It used to be the driver alone who gave up on a boat
     * stuck a few blocks off, leaving the rest sitting in it.
     */
    private fun restingAtBank(vehicle: Entity): Boolean {
        if (vehicle.deltaMovement.horizontalDistance() > BOAT_RESTING_SPEED) return false
        val level = vehicle.level() as? ServerLevel ?: return false
        val cached = bankCache[vehicle.uuid]
        if (cached != null && level.gameTime - cached.first < BANK_CHECK_TICKS) return cached.second
        val bank = com.sbwnpc.squad.vehicle.WaterRoutes.bankBeside(level, Ports.vehicles.hull(vehicle), LANDING_REACH)
        bankCache[vehicle.uuid] = level.gameTime to bank
        if (bankCache.size > 64) bankCache.entries.removeIf { level.gameTime - it.value.first > BANK_CHECK_TICKS }
        return bank
    }

    /** Where the crew of [vehicle], a boat, get off: what its driver's trip set, or the squad's. */
    private fun landingOf(vehicle: Entity): Vec3? =
        VehicleTransportClaims.landingOf(vehicle.uuid) ?: BoatTrips.tripOf(vehicle.uuid)?.route?.takeIf { it.complete }?.landing

    /** [TreeAvoidance] is a few hundred block lookups, so its answer is kept for a few ticks. */
    private fun aroundTrees(entity: NpcEntity, vehicle: Entity, waypoint: Vec3): Vec3 {
        if (entity.tickCount >= nextAvoidanceTick || avoidancePoint == null) {
            nextAvoidanceTick = entity.tickCount + AVOIDANCE_INTERVAL_TICKS
            // Going round a man who stood in the way: headings that run into him are closed too.
            val detouring = entity.tickCount < detourUntilTick
            val point = TreeAvoidance.steerPoint(entity.level(), vehicle, waypoint) { dir ->
                detouring && allyInTheWay(entity, vehicle, dir, DETOUR_LOOKAHEAD) != null
            }
            avoidancePoint = if (point == waypoint) null else point
            if (avoidancePoint != null) DebugFlags.log(LogGroup.VEHICLE, "{} steering round a tree to {}", entity.uuid, point)
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

    private fun resetDriveRoute() {
        driveRoute = emptyList()
        driveRouteComplete = true
        driveSearch = null
        driveLegsOnly = false
    }

    /**
     * The next point on the vehicle's way over the land to [home], from the long-route planner:
     * the whole way round what it can't climb or fit through, as far as the ground is known, and
     * ending where it's cheaper for the crew to walk on. Searched again after the vehicle got stuck
     * ([nextRouteTick]) and short of the end of a way that runs on into ground not yet seen. Null
     * while the first search runs.
     */
    private fun driveWaypoint(entity: NpcEntity, vehicle: Entity, home: Vec3): Vec3? {
        val level = entity.level() as? ServerLevel ?: return home
        if (driveSearch == null && (driveRoute.isEmpty() || entity.tickCount >= nextRouteTick)) {
            val medium = com.sbwnpc.squad.route.Driving(level, vehicle.bbWidth / 2.0, vehicle.maxUpStep().toDouble(), vehicle.bbHeight.toDouble())
            driveSearch = com.sbwnpc.squad.route.CellPlanner.search(medium, vehicle.position(), home)
            driveSearchStarted = entity.tickCount
            if (driveSearch == null) {
                DebugFlags.log(LogGroup.VEHICLE, "{} no long route for {} from {} (not on known open ground), short routes instead",
                    entity.uuid, vehicle.uuid, vehicle.blockPosition())
                driveLegsOnly = true
                return currentWaypoint(entity, home)
            }
        }
        driveSearch?.let { search ->
            if (com.sbwnpc.squad.route.PlanBudget.advance(level, search)) {
                driveSearch = null
                nextRouteTick = Int.MAX_VALUE
                val found = search.result()
                if (found == null || found.route.isEmpty()) {
                    DebugFlags.log(LogGroup.VEHICLE, "{} no long route for {} to {} ({}), short routes instead",
                        entity.uuid, vehicle.uuid, BlockPos.containing(home), search.stoppedBy)
                    driveLegsOnly = true
                    return currentWaypoint(entity, home)
                }
                driveRoute = found.route
                routeIndex = 0
                driveRouteComplete = found.complete
                if (found.complete) VehicleTransportClaims.setLanding(vehicle.uuid, found.landing)
                else VehicleTransportClaims.clearLanding(vehicle.uuid)
                DebugFlags.log(LogGroup.VEHICLE,
                    "{} driving {} along {} points, {} blocks, {} {} ({} from home; {} units over {} ticks)",
                    entity.uuid, vehicle.uuid, driveRoute.size, found.length.toInt(),
                    if (found.complete) "crew out at" else "on toward the goal, to look again at", BlockPos.containing(found.landing),
                    horizontalDistance(found.landing, home).toInt(), search.expanded, entity.tickCount - driveSearchStarted + 1
                )
            } else if (driveRoute.isEmpty()) {
                return null
            }
        }
        var target = driveRoute[routeIndex.coerceIn(driveRoute.indices)]
        while (routeIndex < driveRoute.size - 1 && horizontalDistance(vehicle.position(), target) < WAYPOINT_RADIUS) {
            routeIndex++
            target = driveRoute[routeIndex]
        }
        // Nearing the end of a way that runs on into ground not yet seen: the next stretch.
        if (!driveRouteComplete && driveSearch == null && entity.tickCount >= driveLookAheadTick &&
            horizontalDistance(vehicle.position(), driveRoute.last()) < DRIVE_LOOKAHEAD
        ) {
            driveLookAheadTick = entity.tickCount + BOAT_LOOKAHEAD_RETRY_TICKS
            nextRouteTick = entity.tickCount
        }
        return target
    }

    /** (Re)computes a route to [home] with the driver's own pathfinder, throttled to once every
     *  [ROUTE_RECOMPUTE_TICKS] (or immediately after a stuck-recovery episode, or after exhausting the
     *  current route short of home — see below — via [nextRouteTick] being force-reset). Falls back to
     *  an empty route (steer straight at [home]) if the pathfinder can't find anything, rather than
     *  getting stuck on a route that no longer exists. */
    private fun currentWaypoint(entity: NpcEntity, home: Vec3): Vec3 {
        // A failed search is still a search: retry only when due, not every tick on an empty path.
        val vehicle = entity.vehicle
        if (entity.tickCount >= nextRouteTick && vehicle != null) {
            // Sized to the hull (VehicleRoutes); null means another vehicle had this tick's search.
            val path = VehicleRoutes.plan(entity, vehicle, home)
            if (path != null || route.isEmpty()) {
                lastRouteSearchTick = entity.tickCount
                nextRouteTick = entity.tickCount + if (path != null) ROUTE_RECOMPUTE_TICKS else 1
            }
            if (path != null) {
                route = (0 until path.nodeCount).map { path.getNodePos(it) }
                routeIndex = 0
                DebugFlags.log(LogGroup.VEHICLE,
                    "{} recomputed route: {} nodes, canReach={}, dist-to-home={}",
                    entity.uuid, route.size, path.canReach(), entity.position().distanceTo(home)
                )
            }
        }
        if (route.isEmpty() || vehicle == null) return home

        var target = VehicleRoutes.centreOf(route[routeIndex.coerceIn(route.indices)], vehicle)
        while (routeIndex < route.size - 1 && entity.position().distanceTo(target) < WAYPOINT_RADIUS) {
            routeIndex++
            target = VehicleRoutes.centreOf(route[routeIndex], vehicle)
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
        if (isBoat(vehicle)) workBoatGun(entity, vehicle)
        // Nobody at the wheel and going nowhere: the driver got off, or never came. Waiting in it
        // only ends when the trip's time runs out.
        if (!isPermanentCrew(entity, vehicle) && Ports.vehicles.seating(vehicle).firstOrNull() == null &&
            vehicle.deltaMovement.horizontalDistance() <= BOAT_RESTING_SPEED
        ) {
            if (driverlessSince < 0) driverlessSince = entity.tickCount
            if (entity.tickCount - driverlessSince > DRIVERLESS_TICKS) {
                driverlessSince = -1
                waitToStopThenDismount(entity, vehicle, isDriver = false)
                return
            }
        } else {
            driverlessSince = -1
        }
        val home = resolveTripDestination(entity) ?: return
        if (arrived(entity, vehicle, home)) {
            waitToStopThenDismount(entity, vehicle, isDriver = false)
            return
        }
        // Not on a boat: out on the water that's a swim. A boat whose driver gives up leaves them
        // with nobody at the wheel, and that gets them off (above).
        if (!isBoat(vehicle) && entity.tickCount - boardTick > MAX_TRANSIT_TICKS) {
            waitToStopThenDismount(entity, vehicle, isDriver = false)
        }
    }

    /**
     * The man at a boat's machine gun fires it on the way at whatever his target sensor picked —
     * enemy crews and aircrew first, the whole circle round — while it's in sight and the gun has
     * rounds. SBW lays and fires the gun itself once it has a target, as for a tank's gunner.
     */
    private fun workBoatGun(entity: NpcEntity, vehicle: Entity) {
        if (!Ports.vehicles.hasWeaponAt(vehicle, entity)) return
        val target = entity.target?.takeIf {
            it.isAlive && SquadTeams.isHostile(entity, it) && entity.sensing.hasLineOfSight(it)
        }?.takeIf { Ports.vehicles.seatHasAmmo(vehicle, entity) }
        val aimed = boatGunTarget
        if (target?.uuid != aimed) {
            DebugFlags.log(LogGroup.BOAT,
                "{} at the boat's gun: {}", entity.uuid.toString().take(8),
                when {
                    target != null -> "firing at ${target.uuid.toString().take(8)} ${target.type.descriptionId}"
                    !Ports.vehicles.seatHasAmmo(vehicle, entity) -> "out of ammunition"
                    else -> "nothing in sight"
                }
            )
            boatGunTarget = target?.uuid
        }
        // SBW keeps firing at the last target it was given: always tell it, target or none.
        Ports.vehicles.aimAt(vehicle, entity, target)
    }

    private fun tickCombatDismount(entity: NpcEntity, mountedVehicle: Entity? = null) {
        val vehicle = mountedVehicle ?: mountOrAbort(entity) ?: return
        if (VehicleTransportClaims.combatGunnerOf(vehicle.uuid) == entity.uuid && gunnerStillEngaged(entity)) {
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
            DebugFlags.log(LogGroup.VEHICLE,
                "{} dismounting: speed={} slowEnough={} waitedTooLong={} health={}/{} pos={}",
                entity.uuid, kotlin.math.sqrt(speedSqr), slowEnough, waitedTooLong,
                entity.health, entity.maxHealth, vehicle.position()
            )
            // A gun left with a target keeps firing at it with nobody aboard.
            if (Ports.vehicles.hasWeaponAt(vehicle, entity)) Ports.vehicles.aimAt(vehicle, entity, null)
            boatGunTarget = null
            entity.stopRiding()
            releaseVehicleTeamIfLastAboard(vehicle, entity)
            arrivalWaitStartTick = -1
            if (isBoat(vehicle)) boatCooldownUntilTick = entity.tickCount + BOAT_COOLDOWN_TICKS
            // Out where the drive ends — at the goal, or where walking on beats driving: the rest of
            // this order is on foot. Still further off than the distance worth a ride, the crew
            // climbed straight back in, was refused, and got out again.
            else entity.currentSquad()?.let { noVehicleForStamp = it.orderStamp }
        }
    }

    private fun resolveTripDestination(entity: NpcEntity): Vec3? {
        // A boat's trip ends at its landing, whatever the order says about where the squad goes after.
        // Read live: the driver may have found another way, to another bank, since boarding. Only
        // the driver steers for the end of a stretch that goes on; the rest wait for a bank.
        entity.vehicle?.takeIf(::isBoat)?.let {
            return if (VehicleTransportClaims.driverOf(it.uuid) == entity.uuid) landingOf(it) ?: tripDestination else landingOf(it)
        }
        // Where the driver's way ends, if it has planned one — the whole crew gets out there.
        if (entity.vehicle != null && VehicleTransportClaims.driverOf(entity.vehicle!!.uuid) != entity.uuid) {
            VehicleTransportClaims.landingOf(entity.vehicle!!.uuid)?.let { return it }
        }
        val order = entity.currentSquad()?.order
        if (order != SquadOrder.MOVE && order != SquadOrder.RETREAT) return tripDestination
        val destination = entity.homeCenter()
        if (destination != tripDestination) {
            tripDestination = destination
            route = emptyList()
            routeIndex = 0
            nextRouteTick = entity.tickCount
            resetDriveRoute()
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
        val order = entity.currentSquad()?.order
        if (order == SquadOrder.ATTACK || order == SquadOrder.RETREAT) return false
        return engageVehicleThreat(entity, vehicle, attacker)
    }

    private fun isPermanentCrew(entity: NpcEntity, vehicle: Entity): Boolean =
        entity.assignedVehicleId == vehicle.uuid

    private fun engageVehicleThreat(entity: NpcEntity, vehicle: Entity): Boolean {
        val threat = entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) } ?: return false
        return engageVehicleThreat(entity, vehicle, threat)
    }

    private fun engageVehicleThreat(entity: NpcEntity, vehicle: Entity, threat: LivingEntity): Boolean {
        // Attacking drives through it; so does falling back — stopping to fight is the opposite of
        // getting away. The gunner still shoots on the move.
        val order = entity.currentSquad()?.order
        if (order == SquadOrder.ATTACK || order == SquadOrder.RETREAT) return false
        // Stopping on the water only leaves everyone to swim under fire: keep going to the bank.
        if (isBoat(vehicle)) return false

        assignCombatGunner(vehicle)?.let { gunner ->
            gunner.rememberVehicleAttacker(threat)
            BrainUtils.setTargetOfEntity(gunner, threat)
        }
        phase = Phase.COMBAT_DISMOUNT
        gunnerSawThreatTick = entity.tickCount
        DebugFlags.log(LogGroup.VEHICLE,
            "{} stopping {} for combat against {}",
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

    /**
     * The gunner stays on the gun while it has something to shoot at. A live target somewhere it
     * can no longer see is not that: the rest have long since dismounted, and it sat alone in the
     * turret for as long as that enemy lived, anywhere on the map.
     */
    private fun gunnerStillEngaged(entity: NpcEntity): Boolean {
        val target = entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) } ?: return false
        if (entity.sensing.hasLineOfSight(target)) gunnerSawThreatTick = entity.tickCount
        return entity.tickCount - gunnerSawThreatTick < GUNNER_LOST_SIGHT_TICKS
    }

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

    private fun isBoat(vehicle: Entity): Boolean = Ports.vehicles.mobility(vehicle) == Mobility.WATER

    /** Afloat with a free seat, charged, and nobody's player at the helm. */
    private fun isUsableBoat(vehicle: Entity, entity: NpcEntity): Boolean =
        Ports.vehicles.isOperational(vehicle) && !Ports.vehicles.isLocked(vehicle) && Ports.vehicles.seatCount(vehicle) > 0 &&
            isBoat(vehicle) && vehicle.isInWater && VehiclePower.hasReserve(vehicle) && !hasBlockingPlayerAboard(vehicle, entity)

    /** Close enough to [home] to get off: a boat has to come right up to the landing point, or be
     *  washed up on land — a vehicle on the road stops well short and lets everyone walk in. */
    private fun arrived(entity: NpcEntity, vehicle: Entity, home: Vec3): Boolean {
        if (!isBoat(vehicle)) {
            // Where its way ends: the goal, or where the crew walks on from because driving
            // further costs more than walking — the foot of a climb it can't make.
            VehicleTransportClaims.landingOf(vehicle.uuid)?.let {
                if (horizontalDistance(vehicle.position(), it) <= GROUND_EXIT_RADIUS) return true
            }
            return vehicle.position().distanceTo(home) <= ARRIVAL_RADIUS
        }
        // Under way to the next stretch of water, not to a bank: nobody gets off.
        if (entity.vehicle === vehicle && VehicleTransportClaims.driverOf(vehicle.uuid) == entity.uuid && !boatRouteComplete) return false
        val dist = horizontalDistance(vehicle.position(), home)
        return dist <= BOAT_ARRIVAL_RADIUS ||
            // Washed up by its landing. Anywhere else it ran aground on the way: that's stuck, not there.
            (!vehicle.isInWater && entity.tickCount - boardTick > BOAT_LAUNCH_GRACE_TICKS && dist <= BOAT_BANK_RADIUS) ||
            (dist <= BOAT_BANK_RADIUS && restingAtBank(vehicle))
    }

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

    private fun alliedNpcBlocksTravel(entity: NpcEntity, vehicle: Entity, direction: Vec3): Boolean =
        allyInTheWay(entity, vehicle, direction) != null

    /** The first man of the driver's side the hull would run into going [direction], looking
     *  [reach] blocks ahead — by default as far as the vehicle can brake from its current speed. */
    private fun allyInTheWay(
        entity: NpcEntity,
        vehicle: Entity,
        direction: Vec3,
        reach: Double = maxOf(MIN_ALLY_LOOKAHEAD, vehicle.deltaMovement.horizontalDistance() * ALLY_BRAKE_LOOKAHEAD_TICKS),
    ): NpcEntity? {
        if (direction.lengthSqr() < 1.0e-6) return null
        val lookahead = reach
        val offset = direction.normalize().scale(lookahead)
        val corridor = Ports.vehicles.hull(vehicle).expandTowards(offset.x, offset.y, offset.z).inflate(ALLY_CLEARANCE)
        val faction = SquadTeams.factionOf(entity) ?: return null
        val level = entity.level() as? ServerLevel ?: return null
        val samples = kotlin.math.ceil(lookahead / ALLY_SWEEP_STEP).toInt().coerceIn(1, MAX_ALLY_SWEEP_SAMPLES)
        // Runs every tick while driving — NpcRegistry instead of a corridor box entity query.
        NpcRegistry.forEachIn(level, corridor, exclude = entity) { ally ->
            if (ally.vehicle !== vehicle && ally.isAlive && SquadTeams.factionOf(ally) == faction &&
                (1..samples).any { step -> vehicleOverlaps(vehicle, ally, offset.scale(step.toDouble() / samples)) }
            ) return ally
        }
        return null
    }

    private fun vehicleOverlaps(vehicle: Entity, entity: NpcEntity, offset: Vec3): Boolean =
        Ports.vehicles.wouldHit(vehicle, entity, offset)

    /** Throttle forward and steer at [target] — see the Vehicles adapter for how. */
    private fun steerToward(vehicle: Entity, target: Vec3, throttle: Boolean = true) {
        val steering = Ports.vehicles.driveToward(vehicle, target, throttle)
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
            DebugFlags.log(LogGroup.VEHICLE,
                "steer: {} -> right={} left={}", steering.detail, steering.right, steering.left
            )
        }
    }

    /** A player of ours may ride along in the back; a player at the wheel, or anyone not ours,
     *  keeps the vehicle his — NPCs never take over a player's vehicle. */
    private fun hasBlockingPlayerAboard(vehicle: Entity, entity: NpcEntity): Boolean {
        val players = vehicle.passengers.filterIsInstance<net.minecraft.world.entity.player.Player>()
        if (players.isEmpty()) return false
        // A player at the wheel is driving it himself.
        if (players.any { Ports.vehicles.seatOf(vehicle, it) == 0 }) return true
        // One of ours in the back rides along; anyone else keeps it his.
        return players.any { !DriverAllegiance.isAlliedDriver(it, entity) }
    }

    private fun holdVehicle(vehicle: Entity) {
        stopVehicle(vehicle)
        Ports.vehicles.cutPower(vehicle)
    }

    private fun stopVehicle(vehicle: Entity) = Ports.vehicles.release(vehicle)

    companion object {
        /** Whether each boat was last found resting against a bank, and when — see [restingAtBank];
         *  shared, as everyone aboard asks about the same boat. */
        private val bankCache = HashMap<java.util.UUID, kotlin.Pair<Long, Boolean>>()

        private const val TRANSPORT_DISTANCE_THRESHOLD = 100.0
        private const val SEARCH_RADIUS = 60.0
        private const val BOARD_DISTANCE = 3.0
        private const val ARRIVAL_RADIUS = 20.0
        /** A boat this near its landing point is up against the bank. */
        private const val BOAT_ARRIVAL_RADIUS = 3.0
        /** A boat stuck this near its landing point has run aground by the bank. */
        private const val BOAT_AGROUND_RADIUS = 8.0
        /** A point on a boat's way over the water counts as passed this near. */
        private const val BOAT_WAYPOINT_RADIUS = 5.0
        /** Within this of its landing a boat comes in slowly, at no more than [BOAT_APPROACH_SPEED]. */
        private const val BOAT_SLOW_RADIUS = 14.0
        private const val BOAT_APPROACH_SPEED = 0.25
        /** A boat at rest this near its landing, with ground beside it, has arrived. */
        private const val BOAT_BANK_RADIUS = 12.0
        private const val BOAT_RESTING_SPEED = 0.05
        /** Blocks past a boat's side a man steps out onto the bank — as NpcEntity puts him down. */
        private const val LANDING_REACH = 4
        private const val BANK_CHECK_TICKS = 10L
        /** A boat's reverse-and-turn — slower to get going astern than a wheeled vehicle. */
        private const val BOAT_RECOVERY_TICKS = 60
        /** Steering aims this far along the way ahead of the boat, and this much further per block a tick of speed. */
        private const val BOAT_CARROT_MIN = 6.0
        private const val BOAT_CARROT_PER_SPEED = 14.0
        /** Bends this far ahead, and this much further per block a tick of speed, slow the boat down. */
        private const val BOAT_BRAKE_MIN = 10.0
        private const val BOAT_BRAKE_PER_SPEED = 35.0
        private const val BOAT_FULL_SPEED = 2.0
        /** Round a hairpin at no more than this. */
        private const val BOAT_SLOW_TURN_SPEED = 0.28
        /** Legs of the way looked along for where the boat is on it. */
        private const val BOAT_LEGS_LOOKED_AT = 4
        /** A boat that hasn't got this much nearer the end of its way in [BOAT_NO_PROGRESS_TICKS]
         *  is given up on and everyone gets off. */
        private const val BOAT_PROGRESS_STEP = 4.0
        private const val BOAT_NO_PROGRESS_TICKS = 1200
        /** A vehicle this near where its way ends lets its crew out there. */
        private const val GROUND_EXIT_RADIUS = 6.0
        /** Blocks short of the end of a vehicle's way into unseen ground at which the next stretch is looked for. */
        private const val DRIVE_LOOKAHEAD = 48.0
        /** Blocks short of the end of a way that goes on at which the next stretch is looked for. */
        private const val BOAT_LOOKAHEAD = 40.0
        private const val BOAT_LOOKAHEAD_RETRY_TICKS = 40
        /** Stuck again within this of the last time, a boat's way is searched afresh. */
        private const val BOAT_RESTUCK_TICKS = 300
        /** Riding a boat with nobody at the wheel this long, at rest, before getting off. */
        private const val DRIVERLESS_TICKS = 40
        /** A boat out of the water this soon after boarding is still being pushed off, not landed. */
        private const val BOAT_LAUNCH_GRACE_TICKS = 60
        /** After landing, or finding no boat, before the water ahead is looked at again. */
        private const val BOAT_COOLDOWN_TICKS = 600
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
        private const val ALLY_WAIT_TICKS = 40 // 2s for a man in the way to move off before going round
        private const val DETOUR_TICKS = 60 // 3s steering clear of him after backing off
        private const val DETOUR_LOOKAHEAD = 8.0
        private const val STUCK_CHECK_INTERVAL_TICKS = 40 // 2s between progress checks
        private const val STUCK_DISTANCE_SQR = 1.0 // moved less than 1 block in that window
        /** Turned more than this in that window — a tank turning on the spot — isn't stuck. */
        private const val STUCK_TURN_DEGREES = 20f
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
        private const val GUNNER_LOST_SIGHT_TICKS = 200
        private const val AVOIDANCE_INTERVAL_TICKS = 5

        /** Clears the temporary squad team after the final NPC leaves or dies in the vehicle. */
        fun releaseVehicleTeamIfLastAboard(vehicle: Entity, leaving: NpcEntity) {
            if (vehicle.passengers.any { it is NpcEntity && it !== leaving }) return
            SquadTeams.clear(vehicle)
        }
    }
}

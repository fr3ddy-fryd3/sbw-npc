package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.mojang.datafixers.util.Pair
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * Squad-transit-by-vehicle: when a squad's objective is far enough away that walking is silly, NPCs
 * seek out nearby vehicles instead of hoofing it. No autonomy beyond that yet — no faction ownership
 * tool, no combat while mounted, no reaction to the vehicle taking fire; those are deliberately
 * follow-up work. See PLAN.md Этап 9.
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
 */
class VehicleTransportBehaviour : ExtendedBehaviour<NpcEntity>() {

    // Indefinite by design, same reasoning as every other long-running behaviour in this codebase
    // (see SeekCoverBehaviour's doc comment for the full ExtendedBehaviour-timeout story).
    init {
        noTimeout()
    }

    private enum class Phase { SEEKING, BOARDING, WAITING_FOR_SQUAD, DRIVING, RIDING }

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

    // Stuck detection while DRIVING — a vehicle's own physics has no pathfinding of its own (just
    // raw input flags), and the route above is only a walking-mob-shaped guide, not a guarantee, so
    // without this a stretch the vehicle genuinely can't get through would stop it dead forever.
    private var lastStuckCheckTick = 0
    private var lastStuckCheckPos: Vec3? = null
    private var recoveryUntilTick = 0
    private var recoveryTurnLeft = false

    // Hysteresis state for steerToward — see its own doc comment.
    private var turningLeft = false
    private var turningRight = false

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun combatInterrupted(entity: NpcEntity) =
        entity.target != null || entity.isAlert() || entity.isSuppressed()

    /** [checkGiveup] must be false when called from [checkExtraStartConditions] (deciding whether to
     *  START a brand new episode) and true from [shouldKeepRunning] (deciding whether an ALREADY
     *  RUNNING seeking episode has taken too long) — only [shouldKeepRunning] may actually TRIGGER a
     *  giveup (setting [giveupCooldownUntilTick]); [checkExtraStartConditions] only ever reads it.
     *
     *  The giveup itself is an absolute-time cooldown, not an episode-scoped flag: a first attempt at
     *  this (resetting a per-episode timestamp in `stop()`) still let the behaviour restart on the
     *  very next tick after giving up — nothing ever blocked `checkExtraStartConditions` from seeing
     *  a freshly-reset timer and immediately starting a new episode, so it never actually handed
     *  control to SquadOrderBehaviour's walking fallback, just spun restart→giveup→restart. A
     *  standing cooldown independent of phase/episode state is what actually keeps it stopped for a
     *  while. It still doesn't block a genuinely fresh distant order from working (that's the
     *  original bug this replaced): cooldown only gets set by an actual giveup, never by mere elapsed
     *  idle time, so an order arriving long after the last episode ended starts with no cooldown at
     *  all. Cheap checks (combat/dug-in/cooldown) run before the squad/home/distance lookups, and the
     *  log line's reason is only formatted when actually about to be logged. */
    private fun eligible(entity: NpcEntity, checkGiveup: Boolean): Boolean {
        if (entity.vehicle != null) return true // already mounted: DRIVING/RIDING keep going regardless

        if (combatInterrupted(entity)) {
            return logEligibility(entity, false) {
                "combat interrupted (target=${entity.target != null} alert=${entity.isAlert()} suppressed=${entity.isSuppressed()})"
            }
        }
        if (entity.diggedIn) return logEligibility(entity, false) { "dug in" }
        if (entity.tickCount < giveupCooldownUntilTick) {
            return logEligibility(entity, false) { "cooling down after a recent giveup (${giveupCooldownUntilTick - entity.tickCount} ticks left)" }
        }

        val squad = entity.currentSquad() ?: return logEligibility(entity, false) { "no squad" }
        if (squad.order == SquadOrder.FREE) return logEligibility(entity, false) { "order is FREE" }
        val home = entity.homeCenter() ?: return logEligibility(entity, false) { "no home/objective" }
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
            com.sbwnpc.squad.SquadMod.LOGGER.info("[vehicle-debug] {} eligible={} : {}", entity.uuid, result, reason())
        }
        return result
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        eligible(entity, checkGiveup = false)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean =
        if (entity.vehicle != null) true else eligible(entity, checkGiveup = true)

    override fun start(entity: NpcEntity) {
        phase = Phase.SEEKING
        targetVehicleId = null
        repathCooldown = 0
        nextSeekTick = 0
        seekingStartTick = entity.tickCount
        lastStuckCheckPos = null
        recoveryUntilTick = 0
        route = emptyList()
        nextRouteTick = 0
        turningLeft = false
        turningRight = false
        entity.vehicleTransport = true
        com.sbwnpc.squad.SquadMod.LOGGER.info(
            "[vehicle-debug] {} starting vehicle transport, home={} dist={}",
            entity.uuid, entity.homeCenter(), entity.homeCenter()?.let { entity.position().distanceTo(it) }
        )
    }

    override fun stop(entity: NpcEntity) {
        VehicleTransportClaims.release(entity.uuid)
        entity.vehicleTransport = false
        phase = Phase.SEEKING
        targetVehicleId = null
        // Without this, a stale seekingStartTick from a previous (long-finished) transport episode
        // survives into the next one — checkExtraStartConditions re-evaluates eligible() the very
        // next tick this NPC becomes eligible again (e.g. a fresh far-away order), sees
        // tickCount - seekingStartTick already far past SEEK_GIVEUP_TICKS from minutes ago, and
        // gives up before ever actually starting. Confirmed via [vehicle-debug]: a squad that had
        // already used a vehicle once, arrived, then got a new distant order walked the whole way
        // on foot — every one of them logged an instant "gave up" instead of "starting".
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
            Phase.RIDING -> tickRiding(entity)
        }
    }

    /** Priority 1: join a vehicle a squadmate already claimed, if it still has a free seat.
     *  Priority 2: claim a fresh, empty vehicle nearby as driver. */
    private fun tickSeeking(entity: NpcEntity, level: ServerLevel) {
        if (entity.tickCount < nextSeekTick) return
        nextSeekTick = entity.tickCount + SEEK_INTERVAL_TICKS

        val squad = entity.currentSquad() ?: return

        val joinable = squad.members.asSequence()
            .filter { it != entity.uuid }
            .mapNotNull { VehicleTransportClaims.vehicleOf(it) }
            .distinct()
            .mapNotNull { level.getEntity(it) as? VehicleEntity }
            .filter { it.isAlive && VehicleTransportClaims.claimedSeats(it.uuid) < it.maxPassengers && !hasPlayerAboard(it) }
            .filter { entity.distanceToSqr(it) <= SEARCH_RADIUS * SEARCH_RADIUS }
            .minByOrNull { entity.distanceToSqr(it) }

        if (joinable != null) {
            if (VehicleTransportClaims.claimPassenger(joinable.uuid, entity.uuid, joinable.maxPassengers)) {
                targetVehicleId = joinable.uuid
                phase = Phase.BOARDING
                com.sbwnpc.squad.SquadMod.LOGGER.info(
                    "[vehicle-debug] {} claimed passenger seat in {}", entity.uuid, joinable.uuid
                )
            }
            return
        }

        val nearby = level.getEntitiesOfClass(
            VehicleEntity::class.java,
            AABB.ofSize(entity.position(), SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2)
        )
        val candidate = nearby.filter {
            it.isAlive && it.maxPassengers > 0 && VehicleTransportClaims.claimedSeats(it.uuid) == 0 &&
                it.passengers.isEmpty()
        }.minByOrNull { entity.distanceToSqr(it) }

        if (candidate == null) {
            if (entity.tickCount - lastNoCandidateLogTick > NO_CANDIDATE_LOG_INTERVAL_TICKS) {
                lastNoCandidateLogTick = entity.tickCount
                com.sbwnpc.squad.SquadMod.LOGGER.info(
                    "[vehicle-debug] {} found no claimable vehicle within {} blocks ({} VehicleEntity total nearby)",
                    entity.uuid, SEARCH_RADIUS, nearby.size
                )
            }
            return
        }

        if (VehicleTransportClaims.claimDriver(candidate.uuid, entity.uuid)) {
            targetVehicleId = candidate.uuid
            phase = Phase.BOARDING
            com.sbwnpc.squad.SquadMod.LOGGER.info(
                "[vehicle-debug] {} claimed driver seat of {}", entity.uuid, candidate.uuid
            )
        }
    }

    private fun tickBoarding(entity: NpcEntity, level: ServerLevel) {
        val vehicle = targetVehicleId?.let { level.getEntity(it) as? VehicleEntity }
        // Re-verified here, not just at claim time in tickSeeking — a player can board/park in the
        // vehicle during the walk over, which the app-level claim registry has no way to see.
        if (vehicle == null || !vehicle.isAlive || hasPlayerAboard(vehicle)) {
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
                entity.navigation.moveTo(vehicle.x, vehicle.y, vehicle.z, RUN_SPEED_MODIFIER)
                repathCooldown = REPATH_INTERVAL_TICKS
            }
            return
        }

        entity.navigation.stop()
        // Not force=true: lets VehicleEntity.canAddPassenger's own real seat-capacity check apply as
        // a final backstop, instead of only trusting the app-level claim registry.
        if (!entity.startRiding(vehicle, false)) {
            com.sbwnpc.squad.SquadMod.LOGGER.info(
                "[vehicle-debug] {} in range of {} but startRiding refused", entity.uuid, vehicle.uuid
            )
            return
        }
        com.sbwnpc.squad.SquadMod.LOGGER.info("[vehicle-debug] {} boarded {}", entity.uuid, vehicle.uuid)

        entity.currentSquad()?.faction?.let { SquadTeams.assign(vehicle, it) }

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
    private fun mountOrAbort(entity: NpcEntity): VehicleEntity? {
        val vehicle = entity.vehicle as? VehicleEntity
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
        stopVehicle(vehicle)

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
        val home = entity.homeCenter()
        if (home == null) {
            stopVehicle(vehicle)
            entity.stopRiding()
            return
        }

        if (vehicle.position().distanceTo(home) <= ARRIVAL_RADIUS) {
            stopVehicle(vehicle)
            entity.stopRiding()
            return
        }

        // Hard cap: recovery below isn't guaranteed to work (e.g. genuinely boxed in) — give up and
        // let the driver walk the rest rather than sit there forever retrying.
        if (entity.tickCount - boardTick > MAX_TRANSIT_TICKS) {
            stopVehicle(vehicle)
            entity.stopRiding()
            return
        }

        if (entity.tickCount < recoveryUntilTick) {
            performRecovery(vehicle)
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
                performRecovery(vehicle)
                return
            }
        }

        steerToward(vehicle, currentWaypoint(entity, home))
    }

    /** (Re)computes a route to [home] with the driver's own pathfinder, throttled to once every
     *  [ROUTE_RECOMPUTE_TICKS] (or immediately after a stuck-recovery episode, or after exhausting the
     *  current route short of home — see below — via [nextRouteTick] being force-reset). Falls back to
     *  an empty route (steer straight at [home]) if the pathfinder can't find anything, rather than
     *  getting stuck on a route that no longer exists. */
    private fun currentWaypoint(entity: NpcEntity, home: Vec3): Vec3 {
        if (route.isEmpty() || entity.tickCount >= nextRouteTick) {
            nextRouteTick = entity.tickCount + ROUTE_RECOMPUTE_TICKS
            val path = entity.navigation.createPath(BlockPos.containing(home), 0)
            route = if (path != null) (0 until path.nodeCount).map { path.getNodePos(it) } else emptyList()
            routeIndex = 0
            com.sbwnpc.squad.SquadMod.LOGGER.info(
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
        // overshooting and turning back onto it every tick — reported in-game as the vehicle circling
        // right where the route ran out. Forcing the next tick's currentWaypoint call to recompute
        // (same idiom already used to force a fresh route right after stuck-recovery) turns each
        // exhausted partial route into "immediately path another ~70 blocks toward home" instead.
        if (routeIndex == route.size - 1 && entity.position().distanceTo(target) < WAYPOINT_RADIUS) {
            nextRouteTick = entity.tickCount
        }
        return target
    }

    /** Back up and turn away, blind to what's actually in the way — there's no sensing here, just
     *  "whatever stopped it, reversing and picking a different heading tends to clear it". */
    private fun performRecovery(vehicle: VehicleEntity) {
        vehicle.forwardInputDown = false
        vehicle.backInputDown = true
        vehicle.leftInputDown = recoveryTurnLeft
        vehicle.rightInputDown = !recoveryTurnLeft
    }

    /** Passenger only: no driving of its own — just watches for the vehicle actually getting close
     *  enough and dismounts itself, independently of what the driver's own instance is doing. Also
     *  bails out on the same hard cap as the driver, in case the driver gave up (or died) and left
     *  the vehicle stranded with nobody driving it. */
    private fun tickRiding(entity: NpcEntity) {
        val vehicle = mountOrAbort(entity) ?: return
        val home = entity.homeCenter() ?: run { entity.stopRiding(); return }
        if (vehicle.position().distanceTo(home) <= ARRIVAL_RADIUS) {
            entity.stopRiding()
            return
        }
        if (entity.tickCount - boardTick > MAX_TRANSIT_TICKS) entity.stopRiding()
    }

    /** Simple steer-toward-point: always throttle forward, turn left/right to close the heading gap.
     *  Sign of the turn inputs is derived from VehicleEngineUtils' own steering math (rightInputDown
     *  increases yRot, leftInputDown decreases it). VehicleVecUtils.getYRotFromVector's raw output is
     *  the negation of yRot's own convention — every other call site in SuperbWarfare that compares
     *  it against yRot negates it first (e.g. VehicleEntity.updateRotation) — so it's negated here too.
     *
     *  Hysteresis, not a single deadzone: a plain "turn right if diff > X else left if diff < -X"
     *  bang-bang controller oscillates right at the boundary (reported in-game as the vehicle
     *  "snaking" down a straight line) because the vehicle's own momentum/turning carries yRot past
     *  the threshold and immediately back every tick. Engaging a turn needs a bigger heading gap than
     *  releasing one, so the input holds steady through the small corrections around dead-on. */
    private fun steerToward(vehicle: VehicleEntity, target: Vec3) {
        val toTarget = target.subtract(vehicle.position())
        val desiredYaw: Double = -VehicleVecUtils.getYRotFromVector(toTarget)
        val diff = Mth.wrapDegrees(desiredYaw - vehicle.yRot.toDouble())

        when {
            diff > TURN_ENGAGE_DEGREES -> { turningRight = true; turningLeft = false }
            diff < -TURN_ENGAGE_DEGREES -> { turningLeft = true; turningRight = false }
            Math.abs(diff) < TURN_RELEASE_DEGREES -> { turningRight = false; turningLeft = false }
            // else: within the hysteresis band — keep whatever turn state was already active
        }

        vehicle.forwardInputDown = true
        vehicle.backInputDown = false
        vehicle.rightInputDown = turningRight
        vehicle.leftInputDown = turningLeft
    }

    /** Never claim/board a vehicle a real player is riding — the claim registry only tracks our own
     *  NPCs, so a player boarding through the ordinary interact path is otherwise invisible to it. */
    private fun hasPlayerAboard(vehicle: VehicleEntity) =
        vehicle.passengers.any { it is net.minecraft.world.entity.player.Player }

    private fun stopVehicle(vehicle: VehicleEntity) {
        vehicle.forwardInputDown = false
        vehicle.backInputDown = false
        vehicle.leftInputDown = false
        vehicle.rightInputDown = false
    }

    companion object {
        private const val TRANSPORT_DISTANCE_THRESHOLD = 100.0
        private const val SEARCH_RADIUS = 60.0
        private const val BOARD_DISTANCE = 3.0
        private const val ARRIVAL_RADIUS = 20.0
        private const val WAIT_TIMEOUT_TICKS = 400 // ~20s
        private const val SEEK_INTERVAL_TICKS = 20
        private const val SEEK_GIVEUP_TICKS = 200 // ~10s of scanning before falling back to walking
        private const val GIVEUP_COOLDOWN_TICKS = 400 // ~20s before trying again after a giveup
        private const val NO_CANDIDATE_LOG_INTERVAL_TICKS = 100
        private const val ELIGIBILITY_LOG_INTERVAL_TICKS = 60 // 3s between eligibility trace lines
        private const val REPATH_INTERVAL_TICKS = 20
        private const val TURN_ENGAGE_DEGREES = 10.0
        private const val TURN_RELEASE_DEGREES = 3.0
        private const val RUN_SPEED_MODIFIER = 1.0
        private const val STUCK_CHECK_INTERVAL_TICKS = 40 // 2s between progress checks
        private const val STUCK_DISTANCE_SQR = 1.0 // moved less than 1 block in that window
        private const val RECOVERY_TICKS = 30 // ~1.5s reverse-and-turn before retrying
        private const val MAX_TRANSIT_TICKS = 2400 // ~2 min hard cap before giving up and walking
        private const val ROUTE_RECOMPUTE_TICKS = 100 // 5s between route refreshes
        private const val WAYPOINT_RADIUS = 3.0
    }
}

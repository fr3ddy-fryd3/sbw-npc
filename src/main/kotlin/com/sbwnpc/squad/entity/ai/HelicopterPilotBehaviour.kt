package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Airspace
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * Flies a [NpcClass.HELICOPTER_PILOT]'s helicopter: lift off, hold a standoff hover where the
 * gunner can work, and put it back down at the squad's objective when there's nothing to shoot.
 *
 * All of the actual control maths lives in [HelicopterFlightController], which documents how SBW's
 * helicopter really responds — the short version being that the collective is `forwardInputDown`,
 * `upInputDown` is a hover TOGGLE that must never be written, and attitude comes from the cyclic
 * (`mouseMoveSpeedX`/`Y`) rather than from writing rotations.
 *
 * Deliberately conservative about altitude and damage, because unlike the kamikaze drone this
 * airframe is meant to come back: it climbs straight up before going anywhere, cruises well clear
 * of the terrain sampled ahead of it, and breaks off to land while still far above the health where
 * SBW takes the controls away (`health < 10%` puts the aircraft into an unrecoverable spiral —
 * see `VehicleEngineUtils.helicopterEngine`).
 *
 * Seating itself is [VehicleCrewBehaviour]'s job; this only ever runs once already aboard, and only
 * for the pilot, who must hold seat 0 — SBW zeroes every input each tick when a helicopter has no
 * first passenger.
 */
class HelicopterPilotBehaviour : ExtendedBehaviour<NpcEntity>() {
    private enum class Phase { TAKEOFF, TRANSIT, STATION, LANDING }

    private var phase = Phase.TAKEOFF
    /** Roll one tick ago, so the trim can steer on the rate as well as the angle. The engine's own
     *  roll rate lives in `deltaRot`, which it resets and reuses internally, so measure it here. */
    private var lastRoll = 0f
    private var lastContactTick = Int.MIN_VALUE / 2
    private var lastContactPos: Vec3? = null
    private var loiterAngle = 0.0
    private var boardingSince = Int.MIN_VALUE / 2
    private var nextScoutTick = 0
    /** Other helicopters near enough to matter, refreshed on an interval rather than per tick —
     *  see [refreshTraffic]. */
    private var trafficPositions: List<Vec3> = emptyList()
    private var trafficIds: List<java.util.UUID> = emptyList()
    private var nextTrafficTick = 0

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        entity.npcClass == NpcClass.HELICOPTER_PILOT && flyable(entity) != null

    override fun shouldKeepRunning(entity: NpcEntity): Boolean = flyable(entity) != null

    private fun rollRate(heli: VehicleEntity): Float {
        val rate = heli.roll - lastRoll
        lastRoll = heli.roll
        return rate
    }

    override fun start(entity: NpcEntity) {
        // Always start from the ground state and let the height check below promote it. A vehicle
        // that has just been spawned does not reliably report onGround(), and believing it put the
        // aircraft into a cruise turn while it was still sitting on the dirt.
        phase = Phase.TAKEOFF
        entity.vehicleTransport = true
    }

    override fun stop(entity: NpcEntity) {
        (entity.vehicle as? VehicleEntity)?.let(::cutControls)
        entity.vehicleTransport = false
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val heli = flyable(entity) ?: return
        entity.vehicleTransport = true
        entity.navigation.stop()

        // Sampled once a tick, before anything acts on it.
        val rollRate = rollRate(heli)
        val target = engagementTarget(entity)
        if (target != null) {
            lastContactTick = entity.tickCount
            lastContactPos = target.position()
        }
        val home = entity.homeCenter() ?: heli.position()
        val airworthy = healthy(heli) && heli.energy > MIN_RESERVE_ENERGY
        val mission = decideMission(entity, heli, target, home, airworthy)
        // Two aircraft on the same job compute the same station, so hold it apart from whoever
        // else is up here and fly it in its own altitude band.
        refreshTraffic(entity, heli, level)
        val station = Airspace.separate(heli.position(), mission.station, trafficPositions)
        val clearance = mission.clearance + Airspace.clearanceFor(heli.uuid, trafficIds)
        val facing = mission.facing

        // The one invariant that matters: cyclic input tilts the lift vector, so commanding it
        // anywhere near the ground drags the airframe along the deck instead of flying it. Height
        // over the terrain decides that, never onGround().
        val height = heli.y - groundY(level, heli.x, heli.z)
        val airborne = height >= MIN_TRANSLATE_HEIGHT

        // An order to be somewhere else is reason enough to fly, not just an enemy to shoot at —
        // otherwise a squad told to move leaves its helicopter sitting on the pad. Already airborne
        // on a mission with no landing in it (a patrol, holding over a target) keeps it up; from
        // the pad, only a real reason gets it off the ground.
        // A transport holds to the same threshold its passengers use to decide to board, so it
        // never sets off on a hop they were never going to get on.
        val launchDistance =
            if (Helicopters.hasTurret(heli)) RELOCATE_DISTANCE else Helicopters.AIR_TRANSPORT_DISTANCE
        val relocating = HelicopterFlightController.horizontalDistance(heli.position(), mission.anchor) > launchDistance
        // Don't leave the squad on the pad: a transport holds until its passengers are aboard, or
        // until it's clear they aren't coming.
        //
        // The window opens when there is somewhere to go, NOT when the aircraft landed. Timing it
        // from the landing meant a transport parked longer than the timeout had already used its
        // window up, so the order to move and the end of the wait landed on the same tick and it
        // left the instant it was asked to — while its passengers were only then being told the
        // trip was on.
        if (airborne || !relocating) boardingSince = Int.MIN_VALUE / 2
        val boarding = !airborne && relocating && waitingForPassengers(entity, heli)
        val wantsToFly = airworthy && !boarding && (relocating || (airborne && !mission.landOnArrival))

        if (mission.patrol && airborne) scoutForFaction(entity, level)

        if (height < MIN_TRANSLATE_HEIGHT && phase != Phase.LANDING) {
            phase = Phase.TAKEOFF
        }

        // Guarded rather than left to DebugFlags.log alone, because the formatting below would
        // otherwise still run on every sample with the flag off.
        if (DebugFlags.LOGGING_ENABLED && entity.tickCount % TELEMETRY_INTERVAL == 0) {
            DebugFlags.log(
                "[heli-debug] {} phase={} h={} y={} power={} rotor={} pitch={} roll={} vy={} spd={} target={} reloc={} toHome={} boarding={} seats={}",
                entity.uuid, phase, "%.1f".format(height), "%.1f".format(heli.y),
                "%.4f".format(heli.power), "%.4f".format(heli.synchedPropellerRot),
                "%.1f".format(heli.xRot), "%.1f".format(heli.roll),
                "%.3f".format(heli.deltaMovement.y), "%.3f".format(heli.deltaMovement.horizontalDistance()),
                target?.name?.string ?: "none", relocating,
                "%.1f".format(HelicopterFlightController.horizontalDistance(heli.position(), home)),
                boarding, "${heli.getOrderedPassengers().count { it != null }}/${heli.getOrderedPassengers().size}"
            )
        }

        when (phase) {
            Phase.TAKEOFF -> {
                if (!wantsToFly && height < MIN_TRANSLATE_HEIGHT) {
                    idleOnPad(heli, rollRate)
                    return
                }
                // Straight up first: translating at rooftop height is how you fly into a hill.
                val safeY = groundY(level, heli.x, heli.z) + clearance
                climbStraight(heli, safeY, rollRate)
                if (heli.y >= safeY - 1.0) {
                    phase = Phase.TRANSIT
                    DebugFlags.log("[heli-debug] {} airborne, transiting to {}", entity.uuid, station)
                }
            }
            Phase.TRANSIT -> {
                fly(level, heli, station, facing, mission.speed, rollRate, clearance)
                if (HelicopterFlightController.horizontalDistance(heli.position(), station) <= ON_STATION_RADIUS) {
                    onStationReached(mission)
                    phase = if (mission.landOnArrival) Phase.LANDING else Phase.STATION
                }
            }
            Phase.STATION -> {
                fly(level, heli, station, facing, minOf(mission.speed, HOLD_SPEED), rollRate, clearance)
                if (mission.landOnArrival) {
                    phase = Phase.LANDING
                } else if (HelicopterFlightController.horizontalDistance(heli.position(), station) > OFF_STATION_RADIUS) {
                    phase = Phase.TRANSIT
                } else {
                    onStationReached(mission)
                }
            }
            Phase.LANDING -> {
                if (heli.onGround() || height < ON_DECK_HEIGHT) {
                    idleOnPad(heli, rollRate)
                    if (wantsToFly) phase = Phase.TAKEOFF
                    return
                }
                if (wantsToFly) {
                    phase = Phase.TAKEOFF
                    return
                }
                land(level, heli, height, rollRate)
            }
        }
    }

    /** Where to be, what to point at, and whether the trip ends on the ground. */
    private class Mission(
        val station: Vec3,
        val facing: Vec3?,
        val landOnArrival: Boolean,
        val speed: Double,
        /** What "am I already where I belong?" is measured against. For a patrol that is the
         *  centre of the area, not the point on the circle being flown to next — otherwise a
         *  helicopter parked dead on its own objective still sees 70 blocks to the next waypoint
         *  and scrambles the moment it is deployed. */
        val anchor: Vec3 = station,
        /** How high over the terrain this leg is flown. A patrol sits low enough to see and be
         *  seen; anything else keeps the safe margin. */
        val clearance: Double = CRUISE_CLEARANCE,
        val patrol: Boolean = false
    )

    /**
     * Turns the squad's order into a flight plan.
     *
     * ATTACK holds over the fight and only breaks off once contact has genuinely gone quiet — not
     * the instant [engagementTarget] blinks out, because the sensor rescans on an interval and
     * line of sight comes and goes, and trying to land in the middle of that was putting the
     * aircraft on the deck with enemies still up. When it does break off it clears the area first
     * rather than landing on the contested point.
     *
     * DEFEND (and PATROL, which needs nothing of its own here — a helicopter has no use for
     * walking a route) circles the objective slowly instead of parking over it.
     */
    private fun decideMission(
        entity: NpcEntity,
        heli: VehicleEntity,
        target: LivingEntity?,
        home: Vec3,
        airworthy: Boolean
    ): Mission {
        val level = entity.level() as? ServerLevel
        // A transport has no turret; its guns are bolted to the airframe, so there is nothing it
        // can usefully do over a target except get shot at. It flies its cargo and lands.
        val gunship = Helicopters.hasTurret(heli)

        if (!airworthy) return landingMission(level, retirePoint(home))

        if (gunship && target != null) {
            return Mission(standoffPoint(heli, target.position()), target.position(), false, HOLD_SPEED)
        }
        if (gunship && stillInContact(entity)) {
            val contact = lastContactPos ?: home
            return Mission(standoffPoint(heli, contact), contact, false, HOLD_SPEED)
        }
        return when (entity.currentSquad()?.order) {
            SquadOrder.DEFEND, SquadOrder.PATROL -> Mission(
                loiterPoint(home), null, false, LOITER_SPEED,
                anchor = home, clearance = PATROL_CLEARANCE, patrol = true
            )
            // Only a gunship is sent to take a point; a transport told to attack just goes home.
            SquadOrder.ATTACK ->
                if (gunship) landingMission(level, retirePoint(home)) else landingMission(level, home)
            else -> landingMission(level, home)
        }
    }

    /** Puts down on ground that can actually be stood on, not on whatever the heightmap reports —
     *  which counts leaves, and dropping troops into a canopy is a long way down. */
    private fun landingMission(level: ServerLevel?, where: Vec3): Mission {
        val spot = level?.let { Helicopters.findLandingSpot(it, where) } ?: where
        return Mission(spot, null, true, CRUISE_SPEED, anchor = where)
    }

    /**
     * True while squadmates are still on their way to a seat. Bounded by [BOARDING_TIMEOUT_TICKS]
     * so one straggler who can't path to the aircraft — or is pinned down fighting — doesn't keep
     * the whole lift on the ground indefinitely.
     */
    private fun waitingForPassengers(entity: NpcEntity, heli: VehicleEntity): Boolean {
        if (Helicopters.hasTurret(heli)) return false
        if (boardingSince == Int.MIN_VALUE / 2) boardingSince = entity.tickCount
        if (entity.tickCount - boardingSince > BOARDING_TIMEOUT_TICKS) return false
        if (heli.getOrderedPassengers().none { it == null }) return false

        val level = entity.level() as? ServerLevel ?: return false
        val squad = entity.currentSquad() ?: return false
        return squad.members.any { id ->
            if (id == entity.uuid) return@any false
            val member = level.getEntity(id) as? NpcEntity ?: return@any false
            member.isAlive && member.vehicle == null &&
                member.distanceToSqr(heli) <= BOARDING_WAIT_RANGE * BOARDING_WAIT_RANGE
        }
    }

    /**
     * Calls in what it can see from up there. A patrol's real value is as a spotter: contacts go
     * into [TeamAwareness], which every NPC of the faction already consults for targets it has not
     * personally seen.
     *
     * Rate-limited, and the line-of-sight checks are capped, because this is a box query plus
     * raycasts running on an aircraft that is airborne for minutes at a time.
     */
    private fun scoutForFaction(entity: NpcEntity, level: ServerLevel) {
        if (entity.tickCount < nextScoutTick) return
        nextScoutTick = entity.tickCount + SCOUT_INTERVAL_TICKS
        val faction = SquadTeams.factionOf(entity) ?: return
        val box = entity.boundingBox.inflate(SCOUT_RANGE)
        val hostiles = level.getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate !== entity && candidate.isAlive && SquadTeams.isHostile(entity, candidate)
        }
        hostiles.sortBy { entity.distanceToSqr(it) }
        var checks = 0
        for (hostile in hostiles) {
            if (checks >= MAX_SCOUT_SIGHT_CHECKS) break
            if (entity.distanceToSqr(hostile) > SCOUT_RANGE * SCOUT_RANGE) break
            checks++
            if (!entity.sensing.hasLineOfSight(hostile)) continue
            TeamAwareness.report(faction, hostile.uuid, level.gameTime)
        }
    }

    /**
     * Who else is flying nearby. Kept as a snapshot refreshed every [TRAFFIC_INTERVAL_TICKS]
     * rather than a query per tick: helicopters are few, but this runs for every pilot, every
     * tick, for as long as it is airborne.
     *
     * Wrecks and the aircraft under this pilot are excluded; anything else with rotors counts,
     * including a hostile one — nobody wants to be flown into either.
     */
    private fun refreshTraffic(entity: NpcEntity, heli: VehicleEntity, level: ServerLevel) {
        if (entity.tickCount < nextTrafficTick) return
        nextTrafficTick = entity.tickCount + TRAFFIC_INTERVAL_TICKS
        val others = level.getEntitiesOfClass(VehicleEntity::class.java, heli.boundingBox.inflate(Airspace.AWARENESS_RANGE)) {
            it !== heli && it.isAlive && !it.isWreck && Helicopters.isHelicopter(it)
        }
        trafficPositions = others.map { it.position() }
        trafficIds = others.map { it.uuid }
    }

    /** Contact is held for a while after the last confirmed target so a sensor gap or a broken
     *  line of sight doesn't read as "the fight is over". */
    private fun stillInContact(entity: NpcEntity): Boolean {
        if (entity.tickCount - lastContactTick < COMBAT_HOLD_TICKS) return true
        val squad = entity.currentSquad() ?: return false
        if (squad.order != SquadOrder.ATTACK) return false
        val focus = squad.focusEntity ?: return false
        val level = entity.level() as? ServerLevel ?: return false
        return (level.getEntity(focus) as? LivingEntity)?.isAlive == true
    }

    /** Somewhere well clear of the last fight to put down, preferring the squad's own ground. */
    private fun retirePoint(home: Vec3): Vec3 {
        val battle = lastContactPos ?: return home
        if (HelicopterFlightController.horizontalDistance(battle, home) >= RETIRE_DISTANCE) return home
        val away = Vec3(home.x - battle.x, 0.0, home.z - battle.z)
        val dir = if (away.lengthSqr() < 1e-4) Vec3(0.0, 0.0, 1.0) else away.normalize()
        return battle.add(dir.scale(RETIRE_DISTANCE))
    }

    private fun loiterPoint(home: Vec3): Vec3 = Vec3(
        home.x + Math.cos(loiterAngle) * LOITER_RADIUS,
        home.y,
        home.z + Math.sin(loiterAngle) * LOITER_RADIUS
    )

    /** Only the loiter has anywhere further to go once it arrives. */
    private fun onStationReached(mission: Mission) {
        if (mission.landOnArrival || mission.facing != null) return
        loiterAngle += LOITER_STEP
    }

    /** The helicopter this NPC is actually flying: it must be aboard, in seat 0, and the airframe
     *  must still be a going concern. */
    private fun flyable(entity: NpcEntity): VehicleEntity? {
        val vehicle = entity.vehicle as? VehicleEntity ?: return null
        if (!Helicopters.isHelicopter(vehicle) || !vehicle.isAlive || vehicle.isWreck) return null
        if (vehicle.firstPassenger !== entity) return null
        return vehicle
    }

    private fun healthy(heli: VehicleEntity): Boolean =
        heli.health > RETREAT_HEALTH_FRACTION * heli.getMaxHealth()

    private fun engagementTarget(entity: NpcEntity): LivingEntity? =
        entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) }

    /** Nearest point on the standoff ring around [target] — recomputed every tick, so the aircraft
     *  closes to gun range and then simply stops rather than orbiting or overflying. */
    private fun standoffPoint(heli: VehicleEntity, from: Vec3): Vec3 {
        val away = Vec3(heli.x - from.x, 0.0, heli.z - from.z)
        val dir = if (away.lengthSqr() < 1e-4) Vec3(0.0, 0.0, 1.0) else away.normalize()
        return from.add(dir.scale(STANDOFF_RANGE))
    }

    private fun fly(
        level: ServerLevel, heli: VehicleEntity, station: Vec3, facing: Vec3?, speed: Double,
        rollRate: Float, clearance: Double
    ) {
        val desiredY = DroneFlightController.cruiseAltitude(
            terrainAhead(level, heli, station), clearance, heli.y
        )
        val cmd = HelicopterFlightController.steer(
            heli.position(), heli.yRot, heli.xRot, heli.roll, rollRate, heli.deltaMovement,
            station, desiredY, speed, tuningOf(heli), facing
        )
        apply(heli, cmd)
    }

    private fun climbStraight(heli: VehicleEntity, desiredY: Double, rollRate: Float) {
        applyCollective(heli, HelicopterFlightController.collectiveFor(heli.y, desiredY))
        // No cyclic at all on the way up: stick input while the rotor is still spooling snaps the
        // airframe over as soon as authority arrives, and any tilt down here just drags it along
        // the ground. Wings still get trimmed — roll has no restoring force of its own.
        heli.mouseMoveSpeedX = 0f
        heli.mouseMoveSpeedY = 0f
        heli.hoverMode = false
        trimWings(heli, rollRate)
    }

    private fun land(level: ServerLevel, heli: VehicleEntity, height: Double, rollRate: Float) {
        applyCollective(heli, HelicopterFlightController.landingCollective(height, heli.deltaMovement.y, DESCENT_RATE))
        heli.mouseMoveSpeedX = 0f
        heli.mouseMoveSpeedY = 0f
        // Hover mode keeps it level and kills the drift that would otherwise put a skid into a wall.
        heli.hoverMode = true
        trimWings(heli, rollRate)
    }

    private fun apply(heli: VehicleEntity, cmd: HelicopterFlightController.Command) {
        applyCollective(heli, cmd.collective)
        heli.mouseMoveSpeedX = cmd.mouseX
        heli.mouseMoveSpeedY = cmd.mouseY
        heli.hoverMode = cmd.hoverMode
        heli.leftInputDown = cmd.rollLeft
        heli.rightInputDown = cmd.rollRight
    }

    /** Pedals are pure roll in this engine — yaw only ever comes from the cyclic. */
    private fun trimWings(heli: VehicleEntity, rollRate: Float) {
        val (left, right) = HelicopterFlightController.rollTrim(heli.roll, rollRate)
        heli.leftInputDown = left
        heli.rightInputDown = right
    }

    /** Never writes `upInputDown` — SBW reads it as "toggle hover mode" and self-clears it. */
    private fun applyCollective(heli: VehicleEntity, collective: HelicopterFlightController.Collective) {
        heli.forwardInputDown = collective == HelicopterFlightController.Collective.CLIMB
        heli.backInputDown = collective == HelicopterFlightController.Collective.SINK_SLOW
        heli.downInputDown = collective == HelicopterFlightController.Collective.SINK_FAST
    }

    /** Sat on the pad with nothing to do: controls neutral, but keep trimming the wings level so
     *  it doesn't sit there slowly tipping over from whatever roll the last flight left behind. */
    private fun idleOnPad(heli: VehicleEntity, rollRate: Float) {
        cutControls(heli)
        trimWings(heli, rollRate)
        // Actually shut down rather than idle. There is no "engine off" input: with engineStart
        // still set, the engine pushes power back up to 0.045 whenever it drops below 0.04, so a
        // parked helicopter would spin its rotor and drain the battery forever. These are the same
        // public fields the engine itself drives.
        if (heli.engineStart || heli.power > 0f) {
            heli.engineStart = false
            heli.engineStartOver = false
            heli.power = 0f
        }
    }

    private fun cutControls(heli: VehicleEntity) {
        heli.forwardInputDown = false
        heli.backInputDown = false
        heli.downInputDown = false
        heli.leftInputDown = false
        heli.rightInputDown = false
        heli.mouseMoveSpeedX = 0f
        heli.mouseMoveSpeedY = 0f
        heli.hoverMode = false
    }

    /** `computed().engineInfo` is the raw JSON the vehicle data was loaded from — casting it to an
     *  [EngineInfo] silently yields null and leaves the controller on its 1.0 defaults. The
     *  deserialized one lives on the entity, and only appears once the engine has ticked at least
     *  once, which is why the fallbacks below are real rather than defensive. */
    private fun tuningOf(heli: VehicleEntity): HelicopterFlightController.Tuning {
        val engine = heli.engineInfo as? EngineInfo.Helicopter
        return HelicopterFlightController.Tuning(
            authority = heli.synchedPropellerRot,
            yawSpeed = engine?.yawSpeed ?: 1f,
            pitchSpeed = engine?.pitchSpeed ?: 1f
        )
    }

    private fun groundY(level: ServerLevel, x: Double, z: Double): Int =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())

    private fun terrainAhead(level: ServerLevel, heli: VehicleEntity, toward: Vec3): List<Int> {
        val pos = heli.position()
        val dir = Vec3(toward.x - pos.x, 0.0, toward.z - pos.z)
            .let { if (it.lengthSqr() < 1e-6) it else it.normalize() }
        val samples = ArrayList<Int>(LOOKAHEAD_DISTANCES.size + 1)
        samples += groundY(level, pos.x, pos.z)
        for (d in LOOKAHEAD_DISTANCES) samples += groundY(level, pos.x + dir.x * d, pos.z + dir.z * d)
        return samples
    }

    private companion object {
        /** Higher than the drone's, on purpose: this one is supposed to survive the trip. */
        const val CRUISE_CLEARANCE = 28.0
        /** No cyclic below this height over the terrain — see the check in tick(). */
        const val MIN_TRANSLATE_HEIGHT = 6.0
        const val ON_DECK_HEIGHT = 0.6
        const val TELEMETRY_INTERVAL = 20
        const val TRAFFIC_INTERVAL_TICKS = 10
        const val CRUISE_SPEED = 0.85
        const val HOLD_SPEED = 0.15
        /** Close enough for the turret to do real work, far enough not to be parked overhead. */
        const val STANDOFF_RANGE = 20.0
        /** How far the squad's objective has to be before flying there beats sitting on the pad. */
        const val RELOCATE_DISTANCE = 24.0
        /** Clear this far from the last fight before putting down. */
        const val RETIRE_DISTANCE = 80.0
        /** Keep treating the area as hot for this long after the last confirmed target. */
        const val COMBAT_HOLD_TICKS = 200
        const val LOITER_RADIUS = 70.0
        const val LOITER_SPEED = 0.35
        const val LOITER_STEP = Math.PI / 4
        /** Half the transit margin: high enough to clear the trees, low enough to be part of the
         *  fight rather than a dot nobody trades fire with. */
        const val PATROL_CLEARANCE = 14.0
        const val SCOUT_RANGE = 75.0
        const val SCOUT_INTERVAL_TICKS = 20
        const val MAX_SCOUT_SIGHT_CHECKS = 6
        /** How long a transport holds on the pad for its squad before going without them. */
        const val BOARDING_TIMEOUT_TICKS = 400
        const val BOARDING_WAIT_RANGE = 40.0
        const val ON_STATION_RADIUS = 8.0
        const val OFF_STATION_RADIUS = 20.0
        const val DESCENT_RATE = 0.22
        /** Break off far above SBW's 10% "controls gone" threshold. */
        const val RETREAT_HEALTH_FRACTION = 0.35f
        const val MIN_RESERVE_ENERGY = 200_000
        val LOOKAHEAD_DISTANCES = listOf(12.0, 24.0, 40.0, 60.0)
    }
}

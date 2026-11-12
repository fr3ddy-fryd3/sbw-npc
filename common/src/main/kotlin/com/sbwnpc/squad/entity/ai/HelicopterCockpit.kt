package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.ai.tactics.AirSupport
import com.sbwnpc.squad.ai.tactics.FlightMode
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Airspace
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * What an aircraft remembers of its fight (#260): when and where its target was last seen. Kept per
 * aircraft, not per pilot, so the facts its plan reads survive a change of pilot.
 */
class AirContact {
    var seenAt = Long.MIN_VALUE / 2
        private set
    var pos: Vec3? = null
        private set

    /** The target last seen, which the aircraft keeps through its gunner's gaps in sight. */
    var target: java.util.UUID? = null
        private set

    fun observe(target: LivingEntity?, now: Long) {
        if (target == null) return
        seenAt = now
        pos = target.position()
        this.target = target.uuid
    }

    /** Whether a target was seen within [AirSupport.CONTACT_HOLD_TICKS]: a sensor gap or a broken line of sight is no end of the fight. */
    fun recent(now: Long) = now - seenAt < AirSupport.CONTACT_HOLD_TICKS
}

/**
 * Whether anyone aboard can fight: the gunship's turret gunner, or on a transport anyone on the
 * benches with a gun in hand. A player counts, as he can shoot.
 */
fun armedAboard(pilot: Entity, heli: Entity): Boolean {
    val seats = Ports.vehicles.seating(heli)
    if (Helicopters.hasTurret(heli)) return seats.getOrNull(Helicopters.GUNNER_SEAT)?.let { it.isAlive && it !== pilot } == true
    return seats.drop(1).any { rider ->
        rider != null && rider !== pilot && rider.isAlive && (rider is net.minecraft.world.entity.player.Player || (rider is NpcEntity && Ports.guns.inHand(rider) != null))
    }
}

/**
 * Flies a [NpcClass.HELICOPTER_PILOT]'s helicopter the way his aircraft's plan says (#260): the
 * [FlightMode] is the plan's decision, and this works out where that is and flies it — lift off,
 * hold a standoff hover where the gunner can work, orbit, loiter, or put it down.
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
 *
 * Execution keeps the explicit rules that are not the plan's to weigh: never off the pad without a
 * gunner aboard (the take-off safety rule, owner 21.10; the plan's `air.fit` is the separate choice to
 * break off), out of a grounded unflyable aircraft with the enemy about (safety), the wait for
 * passengers (the lift's completion), and the end of a quiet attack (its completion criterion).
 */
class HelicopterCockpit(private val pilot: NpcEntity, private val contact: AirContact) {
    private enum class Phase { TAKEOFF, TRANSIT, STATION, LANDING }

    private var phase = Phase.TAKEOFF

    /** Roll one tick ago, so the trim can steer on the rate as well as the angle. The engine's own
     *  roll rate lives in `deltaRot`, which it resets and reuses internally, so measure it here. */
    private var lastRoll = 0f
    private var loiterAngle = 0.0
    private var boardingSince = Int.MIN_VALUE / 2
    private var nextScoutTick = 0

    /** Other helicopters near enough to matter, refreshed on an interval rather than per tick —
     *  see [refreshTraffic]. */
    private var trafficPositions: List<Vec3> = emptyList()

    /** Only the aircraft actually flying nearby — what the altitude bands are computed against. */
    private var layerIds: List<java.util.UUID> = emptyList()
    private var nextTrafficTick = 0
    private var orbitOrderStamp = Int.MIN_VALUE
    private var orbitSeats = -1
    private var nextOrbitObservation = 0
    private val orbitContact = BenchOrbitContact()
    private val attackCompletion = HelicopterAttackCompletion()
    private var orbitDirection = 1

    /** Other enemies the crew sees round its standoff target ([StandoffStation]), refreshed every [THREAT_INTERVAL] ticks. */
    private var standoffThreats: List<Vec3> = emptyList()
    private var nextThreatTick = 0
    private var threatsFrom: Vec3? = null
    private var standoff: Vec3? = null

    private fun rollRate(heli: Entity): Float {
        val roll = Ports.vehicles.roll(heli)
        val rate = roll - lastRoll
        lastRoll = roll
        return rate
    }

    /** The game time it was last flown on a plan's flight, and the height a hold keeps since. */
    var flownAt = Long.MIN_VALUE
        private set
    private var holdY: Double? = null

    /**
     * Holds the aircraft where it is on every tick the plan has no flight for it (a suspended or
     * cancelled flight, or one not planned yet): level in a hover at the height the hold began, shut
     * down on the ground. Controls left as they were fly on unattended while a changed order is
     * replanned; cut in the air, they drop it.
     */
    fun hold() {
        val level = pilot.level() as? ServerLevel ?: return
        val heli = flyable(pilot) ?: return
        pilot.claim(NpcActivity.VEHICLE, this)
        val rollRate = rollRate(heli)
        if (heli.y - groundY(level, heli.x, heli.z) < ON_DECK_HEIGHT || heli.onGround()) {
            idleOnPad(heli, rollRate)
            return
        }
        val y = holdY ?: heli.y.also { holdY = it }
        applyCollective(heli, HelicopterFlightController.collectiveFor(heli.y, y))
        Ports.vehicles.setCyclic(heli, 0f, 0f)
        Ports.vehicles.setHover(heli, true)
        trimWings(heli, rollRate)
    }

    /** He is out of the seat, or out of the plan: the controls go neutral and the vehicle is his no more. */
    fun release() {
        pilot.vehicle?.takeIf(Ports.vehicles::isVehicle)?.let(::cutControls)
        pilot.release(NpcActivity.VEHICLE, this)
    }

    /**
     * One tick of flying [mode], [target] being the aircraft's one target (#261). A new cockpit
     * starts from the ground state and lets the height check below promote it: a vehicle that has
     * just been spawned does not reliably report onGround(), and believing it put the aircraft into
     * a cruise turn while it was still sitting on the dirt.
     */
    fun fly(mode: FlightMode, target: LivingEntity?) {
        val entity = pilot
        val level = entity.level() as? ServerLevel ?: return
        val heli = flyable(entity) ?: return
        entity.claim(NpcActivity.VEHICLE, this)
        entity.navigation.stop()

        flownAt = level.gameTime
        holdY = null
        // Sampled once a tick, before anything acts on it.
        val rollRate = rollRate(heli)
        completeAttack(entity, heli, level)
        val home = entity.homeCenter() ?: heli.position()
        val airworthy = Helicopters.canFly(heli)
        val armed = armedAboard(entity, heli)
        val mission = missionFor(mode, entity, heli, target, home)
        // Two aircraft on the same job compute the same station, so hold it apart from whoever
        // else is up here and fly it in its own altitude band.
        refreshTraffic(entity, heli, level)
        val station = Airspace.separate(heli.position(), mission.station, trafficPositions)
        // A patrol is flown low on purpose — that was the whole point of PATROL_CLEARANCE — so it
        // never gets stacked. Everything else takes its band.
        val clearance = mission.clearance +
            if (mission.patrol) 0.0 else Airspace.clearanceFor(heli.uuid, layerIds)
        val facing = mission.facing

        // The one invariant that matters: cyclic input tilts the lift vector, so commanding it
        // anywhere near the ground drags the airframe along the deck instead of flying it. Height
        // over the terrain decides that, never onGround().
        val height = heli.y - groundY(level, heli.x, heli.z)
        val airborne = height >= MIN_TRANSLATE_HEIGHT

        // Stuck on the ground with the enemy about: a sitting aircraft is a coffin, so get out and
        // fight on foot. VehicleCrewBehaviour keeps the pilot from climbing back in until the
        // contact is over.
        if (!airworthy && !airborne && target != null) {
            DebugFlags.log(LogGroup.HELI, "{} bailing out of a grounded, unflyable helicopter", entity.uuid)
            cutControls(heli)
            entity.stopRiding()
            return
        }

        // An order to be somewhere else is reason enough to fly, not just an enemy to shoot at —
        // otherwise a squad told to move leaves its helicopter sitting on the pad. Already airborne
        // on a mission with no landing in it (a patrol, holding over a target) keeps it up; from
        // the pad, only a real reason gets it off the ground.
        // A transport holds to the same threshold its passengers use to decide to board, so it
        // never sets off on a hop they were never going to get on.
        // One threshold for both airframes, and for the passengers too (owner, 21.10). A landing is
        // measured from the spot it puts down on, which can be up to 24 blocks from the objective:
        // measured from the objective, an aircraft that had landed 22 off took off again at once.
        val reference = if (mission.landOnArrival) mission.station else mission.anchor
        val relocating = HelicopterFlightController.horizontalDistance(heli.position(), reference) > Helicopters.AIR_TRANSPORT_DISTANCE || mission.attackOrbit
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
        // Never up without a gunner aboard (owner, 21.10): a pilot alone is a target, not a weapon.
        val wantsToFly = airworthy && !boarding && (airborne || armed) && (relocating || (airborne && !mission.landOnArrival))

        if (mission.patrol && airborne) scoutForFaction(entity, level)

        if (height < MIN_TRANSLATE_HEIGHT && phase != Phase.LANDING) {
            phase = Phase.TAKEOFF
        }

        // Guarded rather than left to DebugFlags.log alone, because the formatting below would
        // otherwise still run on every sample with the flag off.
        if (DebugFlags.on(LogGroup.HELI) && entity.tickCount % TELEMETRY_INTERVAL == 0) {
            DebugFlags.log(
                LogGroup.HELI,
                "{} phase={} h={} y={} power={} rotor={} pitch={} roll={} vy={} spd={} target={} reloc={} toHome={} boarding={} seats={}",
                entity.uuid, phase, "%.1f".format(height), "%.1f".format(heli.y),
                "%.4f".format(Ports.vehicles.throttle(heli)), "%.4f".format(Ports.vehicles.rotorSpeed(heli)),
                "%.1f".format(heli.xRot), "%.1f".format(Ports.vehicles.roll(heli)),
                "%.3f".format(heli.deltaMovement.y), "%.3f".format(heli.deltaMovement.horizontalDistance()),
                target?.name?.string ?: "none", relocating,
                "%.1f".format(HelicopterFlightController.horizontalDistance(heli.position(), home)),
                boarding, "${Ports.vehicles.seating(heli).count { it != null }}/${Ports.vehicles.seating(heli).size}",
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
                    DebugFlags.log(LogGroup.HELI, "{} airborne, transiting to {}", entity.uuid, station)
                }
            }

            Phase.TRANSIT -> {
                fly(level, heli, station, facing, mission.speed, rollRate, clearance)
                if (!mission.attackOrbit && HelicopterFlightController.horizontalDistance(heli.position(), station) <= ON_STATION_RADIUS) {
                    onStationReached(mission)
                    phase = if (mission.landOnArrival) Phase.LANDING else Phase.STATION
                }
            }

            Phase.STATION -> {
                if (mission.attackOrbit) {
                    phase = Phase.TRANSIT
                    fly(level, heli, station, facing, mission.speed, rollRate, clearance)
                    return
                }
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
        val patrol: Boolean = false,
        val attackOrbit: Boolean = false,
    )

    /**
     * Where [mode] puts the aircraft this tick.
     *
     * A standoff is held from [target] while there is one, else from the last contact: the sensor
     * rescans on an interval and line of sight comes and goes, and the plan's `hold_contact` keeps
     * the aircraft over the fight through that. A retirement clears the area before landing rather
     * than landing on the contested point. The AH-6 orbit keeps an occupied firing bench toward the
     * centre of confirmed contacts, else the ordered objective. A loiter circles the objective
     * slowly instead of parking over it.
     */
    private fun missionFor(mode: FlightMode, entity: NpcEntity, heli: Entity, target: LivingEntity?, home: Vec3): Mission {
        val level = entity.level() as? ServerLevel
        return when (mode) {
            FlightMode.RETIRE -> landingMission(level, retirePoint(home))

            FlightMode.LAND -> landingMission(level, home)

            FlightMode.ORBIT -> orbitMission(entity, heli, target, home)

            FlightMode.STANDOFF, FlightMode.HOLD_CONTACT -> {
                val from = target?.position() ?: contact.pos ?: home
                Mission(standoffPoint(heli, from, target), from, false, HOLD_SPEED)
            }

            FlightMode.LOITER -> loiterMission(home)
        }
    }

    private fun orbitMission(entity: NpcEntity, heli: Entity, target: LivingEntity?, home: Vec3): Mission {
        // The plan orbits only an attack with a squad; a lost order between its update and this tick lands.
        val squad = entity.currentSquad() ?: return landingMission(entity.level() as? ServerLevel, home)
        val stamp = squad.orderStamp
        val seats = Ports.vehicles.seating(heli)
        val mask = (if (seats.getOrNull(2)?.isAlive == true) 1 else 0) + (if (seats.getOrNull(3)?.isAlive == true) 2 else 0)
        if (stamp != orbitOrderStamp || mask != orbitSeats) {
            orbitOrderStamp = stamp
            orbitSeats = mask
            orbitDirection = BenchAttackOrbit.direction(mask and 1 != 0, mask and 2 != 0)
            DebugFlags.log(LogGroup.HELI, "{} event=orbit_direction direction={} occupiedBenches={} stamp={}", entity.uuid, orbitDirection, mask, stamp)
        }
        val observation = if (entity.tickCount >= nextOrbitObservation) {
            nextOrbitObservation = entity.tickCount + 20
            confirmedBenchContact(entity, heli, target)
        } else {
            null
        }
        val center = orbitContact.resolve(entity.level().gameTime, stamp, home, observation)
        if (DebugFlags.on(LogGroup.HELI) && entity.tickCount % TELEMETRY_INTERVAL == 0) {
            DebugFlags.log(LogGroup.HELI, "{} event=attack_orbit center={} radius={} direction={} contactAge={} objective={}", entity.uuid, center, heli.position().subtract(center).horizontalDistance(), orbitDirection, orbitContact.observedAt?.let { entity.level().gameTime - it }, home)
        }
        return Mission(
            BenchAttackOrbit.waypoint(heli.position(), center, orbitDirection, heli.yRot),
            null,
            false,
            BenchAttackOrbit.SPEED,
            anchor = center,
            clearance = BenchAttackOrbit.CLEARANCE,
            attackOrbit = true,
        )
    }

    private fun loiterMission(home: Vec3): Mission =
        Mission(
            loiterPoint(home),
            null,
            false,
            LOITER_SPEED,
            anchor = home,
            clearance = PATROL_CLEARANCE,
            patrol = true,
        )

    private fun surveying(heli: Entity, squad: Squad): Boolean = squad.objective?.let { HelicopterAttackCompletion.surveys(heli.position(), Vec3.atCenterOf(it)) } ?: true

    private fun completeAttack(entity: NpcEntity, heli: Entity, level: ServerLevel) {
        val squad = entity.currentSquad() ?: return
        if (squad.order != SquadOrder.ATTACK) return
        val crewEngaged = Ports.vehicles.seating(heli).filterIsInstance<NpcEntity>().any { member ->
            member.target?.let { it.isAlive && member.isEnemy(it) } == true
        }
        val reported = squad.tactics.contacts.values.any { contact ->
            level.gameTime - contact.seenAt <= 200 && level.getEntity(contact.id)?.isAlive != false
        }
        val focusAlive = squad.focusEntity?.let { level.getEntity(it)?.isAlive } == true
        // Nothing is seen on the way to the objective, nor from high above it, so the quiet interval
        // would run out where the crew could not have seen anyone and the MOVE order below would put
        // the aircraft down on a fight it never saw (#90, #119). One that has met the enemy is
        // already at the fight, which may be far from the objective.
        val busy = crewEngaged || reported || focusAlive || (contact.pos == null && !surveying(heli, squad))
        if (!attackCompletion.observe(squad.orderStamp, level.gameTime, busy)) return
        val destination = squad.objective ?: net.minecraft.core.BlockPos.containing(contact.pos ?: heli.position())
        val manager = com.sbwnpc.squad.squad.SquadManager.get(level)
        com.sbwnpc.squad.experience.ExperienceAwards.objective(level.server, squad)
        manager.setOrder(squad.id, SquadOrder.MOVE)
        manager.setObjective(level, squad.id, destination)
        com.sbwnpc.squad.squad.SquadReports.landing(level.server, squad)
        DebugFlags.log(LogGroup.HELI, "{} event=attack_completed squad={} order=MOVE destination={}", entity.uuid, squad.id, destination)
    }

    /** Personally visible crew targets, clustered around one contact instead of across the map. */
    private fun confirmedBenchContact(pilot: NpcEntity, heli: Entity, preferred: LivingEntity?): Vec3? {
        val candidates = (listOfNotNull(preferred) + Ports.vehicles.seating(heli).filterIsInstance<NpcEntity>().mapNotNull { it.target })
            .distinctBy { it.uuid }
            .filter { it.isAlive && SquadTeams.isHostile(pilot, it) }
            .filter { com.sbwnpc.squad.combat.DetectionSightline.canSee(pilot, it) }
        val first = candidates.firstOrNull() ?: return null
        val cluster = candidates.filter { it.position().distanceToSqr(first.position()) <= 24.0 * 24.0 }
        return Vec3(cluster.sumOf { it.x } / cluster.size, cluster.sumOf { it.y } / cluster.size, cluster.sumOf { it.z } / cluster.size)
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
    private fun waitingForPassengers(entity: NpcEntity, heli: Entity): Boolean {
        if (Helicopters.hasTurret(heli)) return false
        if (boardingSince == Int.MIN_VALUE / 2) boardingSince = entity.tickCount
        if (entity.tickCount - boardingSince > BOARDING_TIMEOUT_TICKS) return false
        if (Ports.vehicles.seating(heli).none { it == null }) return false

        val level = entity.level() as? ServerLevel ?: return false
        val squad = entity.currentSquad() ?: return false
        // Anyone of his who would ride and is on his way, as far off as a rider looks for his own
        // squad's transport. Held to 40 blocks, it left the moment its squad stood further off (21.10).
        return squad.members.any { id ->
            if (id == entity.uuid) return@any false
            val member = level.getEntity(id) as? NpcEntity ?: return@any false
            member.isAlive && member.vehicle == null && member.npcClass in Helicopters.RIDER_CLASSES &&
                member.distanceTo(heli) <= Helicopters.OWN_TRANSPORT_RANGE && !Helicopters.fightingClose(member)
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
            if (!com.sbwnpc.squad.combat.DetectionSightline.canSee(entity, hostile)) continue
            TeamAwareness.report(faction, hostile.uuid, hostile.position(), level.gameTime, "helicopter ${entity.uuid.toString().take(8)}")
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
    private fun refreshTraffic(entity: NpcEntity, heli: Entity, level: ServerLevel) {
        if (entity.tickCount < nextTrafficTick) return
        nextTrafficTick = entity.tickCount + TRAFFIC_INTERVAL_TICKS
        val others = Ports.vehicles.within(level, heli.boundingBox.inflate(Airspace.AWARENESS_RANGE)) {
            it !== heli && Ports.vehicles.isOperational(it) && Helicopters.isHelicopter(it)
        }
        // Everything nearby counts for lateral separation, parked machines included — hovering
        // over one is no better than hovering over a flying one.
        trafficPositions = others.map { it.position() }
        // Altitude bands are a different question, and answering it with this same list is what
        // sent a single gunship three bands up because three of its squadmates were parked on the
        // pad below it.
        layerIds = others
            .filter { !Helicopters.isGrounded(level, it) }
            .filter { it.distanceToSqr(heli) <= Airspace.LAYER_RANGE * Airspace.LAYER_RANGE }
            .map { it.uuid }
    }

    /** Somewhere well clear of the last fight to put down, preferring the squad's own ground. */
    private fun retirePoint(home: Vec3): Vec3 {
        val battle = contact.pos ?: return home
        if (HelicopterFlightController.horizontalDistance(battle, home) >= RETIRE_DISTANCE) return home
        val away = Vec3(home.x - battle.x, 0.0, home.z - battle.z)
        val dir = if (away.lengthSqr() < 1e-4) Vec3(0.0, 0.0, 1.0) else away.normalize()
        return battle.add(dir.scale(RETIRE_DISTANCE))
    }

    private fun loiterPoint(home: Vec3): Vec3 =
        Vec3(
            home.x + Math.cos(loiterAngle) * LOITER_RADIUS,
            home.y,
            home.z + Math.sin(loiterAngle) * LOITER_RADIUS,
        )

    /** Only the loiter has anywhere further to go once it arrives. */
    private fun onStationReached(mission: Mission) {
        if (mission.landOnArrival || mission.facing != null) return
        loiterAngle += LOITER_STEP
    }

    /** The helicopter this NPC is actually flying: it must be aboard, in seat 0, and the airframe
     *  must still be a going concern. */
    private fun flyable(entity: NpcEntity): Entity? {
        val vehicle = entity.vehicle ?: return null
        if (!Helicopters.isHelicopter(vehicle) || !Ports.vehicles.isOperational(vehicle)) return null
        if (vehicle.firstPassenger !== entity) return null
        return vehicle
    }

    /**
     * The point on the standoff ring around [from] ([StandoffStation]), recomputed every tick so the
     * aircraft closes to gun range and then stops rather than orbiting or overflying. The other
     * enemies it keeps clear of are looked for every [THREAT_INTERVAL] ticks, and at once when the
     * point it stands off from moves on to another man.
     */
    private fun standoffPoint(heli: Entity, from: Vec3, target: LivingEntity?): Vec3 {
        val moved = threatsFrom?.let { HelicopterFlightController.horizontalDistance(it, from) > THREAT_REFRESH_SHIFT } != false
        if (moved || pilot.tickCount >= nextThreatTick) {
            nextThreatTick = pilot.tickCount + THREAT_INTERVAL
            threatsFrom = from
            standoffThreats = threatsAround(heli, from, target)
        }
        val point = StandoffStation.point(heli.position(), from, standoffThreats, STANDOFF_RANGE, standoff)
        standoff = point
        if (DebugFlags.on(LogGroup.HELI) && pilot.tickCount % TELEMETRY_INTERVAL == 0) {
            DebugFlags.log(LogGroup.HELI, "{} event=standoff from={} point={} threats={}", pilot.uuid, from, point, standoffThreats.size)
        }
        return point
    }

    /**
     * Enemies within twice [STANDOFF_RANGE] of [from] that someone aboard has in sight. Aircrew
     * sightings never reach the squad's contacts ([com.sbwnpc.squad.combat.tactics.SquadTactics.observe]
     * skips men in vehicles), so the crew's own eyes are what the pilot keeps clear of.
     */
    private fun threatsAround(heli: Entity, from: Vec3, target: LivingEntity?): List<Vec3> {
        val reach = STANDOFF_RANGE * 2
        val crew = heli.passengers.filterIsInstance<NpcEntity>()
        return heli.level().getEntitiesOfClass(LivingEntity::class.java, AABB(from, from).inflate(reach)) { candidate ->
            candidate !== target && candidate.isAlive && SquadTeams.isHostile(pilot, candidate) &&
                crew.any { DetectionSightline.sight(it, candidate).visibility == DetectionSightline.Visibility.VISIBLE }
        }.map { it.position() }
    }

    private fun fly(
        level: ServerLevel,
        heli: Entity,
        station: Vec3,
        facing: Vec3?,
        speed: Double,
        rollRate: Float,
        clearance: Double,
    ) {
        val desiredY = HelicopterFlightController.cruiseAltitude(
            terrainAhead(level, heli, station),
            clearance,
            heli.y,
            approach = HelicopterFlightController.horizontalDistance(heli.position(), station) <= APPROACH_DISTANCE,
        )
        val cmd = HelicopterFlightController.steer(
            heli.position(), heli.yRot, heli.xRot, Ports.vehicles.roll(heli), rollRate, heli.deltaMovement,
            station, desiredY, speed, tuningOf(heli), facing,
        )
        apply(heli, cmd)
    }

    private fun climbStraight(heli: Entity, desiredY: Double, rollRate: Float) {
        applyCollective(heli, HelicopterFlightController.collectiveFor(heli.y, desiredY))
        // No cyclic at all on the way up: stick input while the rotor is still spooling snaps the
        // airframe over as soon as authority arrives, and any tilt down here just drags it along
        // the ground. Wings still get trimmed — roll has no restoring force of its own.
        Ports.vehicles.setCyclic(heli, 0f, 0f)
        Ports.vehicles.setHover(heli, false)
        trimWings(heli, rollRate)
    }

    private fun land(level: ServerLevel, heli: Entity, height: Double, rollRate: Float) {
        applyCollective(heli, HelicopterFlightController.landingCollective(height, heli.deltaMovement.y, DESCENT_RATE))
        Ports.vehicles.setCyclic(heli, 0f, 0f)
        // Hover mode keeps it level and kills the drift that would otherwise put a skid into a wall.
        Ports.vehicles.setHover(heli, true)
        trimWings(heli, rollRate)
    }

    private fun apply(heli: Entity, cmd: HelicopterFlightController.Command) {
        applyCollective(heli, cmd.collective)
        Ports.vehicles.setCyclic(heli, cmd.mouseX, cmd.mouseY)
        Ports.vehicles.setHover(heli, cmd.hoverMode)
        Ports.vehicles.setRollInputs(heli, cmd.rollLeft, cmd.rollRight)
    }

    /** Pedals are pure roll in this engine — yaw only ever comes from the cyclic. */
    private fun trimWings(heli: Entity, rollRate: Float) {
        val (left, right) = HelicopterFlightController.rollTrim(Ports.vehicles.roll(heli), rollRate)
        Ports.vehicles.setRollInputs(heli, left, right)
    }

    private fun applyCollective(heli: Entity, collective: HelicopterFlightController.Collective) {
        Ports.vehicles.setCollective(
            heli,
            climb = collective == HelicopterFlightController.Collective.CLIMB,
            sinkSlow = collective == HelicopterFlightController.Collective.SINK_SLOW,
            sinkFast = collective == HelicopterFlightController.Collective.SINK_FAST,
        )
    }

    /** Sat on the pad with nothing to do: controls neutral, but keep trimming the wings level so
     *  it doesn't sit there slowly tipping over from whatever roll the last flight left behind. */
    private fun idleOnPad(heli: Entity, rollRate: Float) {
        cutControls(heli)
        trimWings(heli, rollRate)
        // Actually shut down rather than idle, or a parked helicopter spins its rotor and drains the
        // battery forever.
        Ports.vehicles.shutDownEngine(heli)
    }

    private fun cutControls(heli: Entity) = Ports.vehicles.neutralControls(heli)

    /** The turn rates only appear once the engine has run at least once, which is why the
     *  fallbacks below are real rather than defensive. */
    private fun tuningOf(heli: Entity): HelicopterFlightController.Tuning {
        val rates = Ports.vehicles.turnRates(heli)
        return HelicopterFlightController.Tuning(
            authority = Ports.vehicles.rotorSpeed(heli),
            yawSpeed = rates?.yaw ?: 1f,
            pitchSpeed = rates?.pitch ?: 1f,
        )
    }

    private fun groundY(level: ServerLevel, x: Double, z: Double): Int = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z))

    private fun terrainAhead(level: ServerLevel, heli: Entity, toward: Vec3): List<Int> {
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

        /**
         * The leg to a landing spot. Half again faster (headless, 01.11), it overshot the spot by 10
         * to 17 blocks; braking for it, it circled the spot wider than [ON_STATION_RADIUS].
         */
        const val CRUISE_SPEED = 0.85
        const val HOLD_SPEED = 0.15

        /** Close enough for the turret to do real work, far enough not to be parked overhead. */
        const val STANDOFF_RANGE = 20.0
        private const val THREAT_INTERVAL = 20

        /** Blocks the stood-off point may move before its neighbours are looked for again: another man, not the same one walking. */
        private const val THREAT_REFRESH_SHIFT = 5.0

        /** Clear this far from the last fight before putting down. */
        const val RETIRE_DISTANCE = 80.0

        /** Keep treating the area as hot for this long after the last confirmed target. */
        const val COMBAT_HOLD_TICKS = 200
        const val LOITER_RADIUS = 70.0

        /** Half again the first 0.35: the aircraft crawled about its area (in-game 01.11). */
        const val LOITER_SPEED = 0.525
        const val LOITER_STEP = Math.PI / 4

        /** Half the transit margin: high enough to clear the trees, low enough to be part of the
         *  fight rather than a dot nobody trades fire with. */
        const val PATROL_CLEARANCE = 14.0
        const val SCOUT_RANGE = 75.0
        const val SCOUT_INTERVAL_TICKS = 20
        const val MAX_SCOUT_SIGHT_CHECKS = 6

        /** How long a transport holds on the pad for its squad before going without them. */
        const val BOARDING_TIMEOUT_TICKS = 800
        const val ON_STATION_RADIUS = 8.0
        const val OFF_STATION_RADIUS = 20.0
        const val DESCENT_RATE = 0.22
        val LOOKAHEAD_DISTANCES = listOf(12.0, 24.0, 40.0, 60.0)

        /** Within this of its station a helicopter comes down from whatever height the trip left it at. */
        const val APPROACH_DISTANCE = 150.0
    }
}

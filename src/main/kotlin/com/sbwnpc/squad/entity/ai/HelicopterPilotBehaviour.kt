package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
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
        val station = mission.station
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
        val relocating = HelicopterFlightController.horizontalDistance(heli.position(), mission.anchor) > RELOCATE_DISTANCE
        val wantsToFly = airworthy && (relocating || (airborne && !mission.landOnArrival))

        if (height < MIN_TRANSLATE_HEIGHT && phase != Phase.LANDING) {
            phase = Phase.TAKEOFF
        }

        // Guarded rather than left to DebugFlags.log alone, because the formatting below would
        // otherwise still run on every sample with the flag off.
        if (DebugFlags.LOGGING_ENABLED && entity.tickCount % TELEMETRY_INTERVAL == 0) {
            DebugFlags.log(
                "[heli-debug] {} phase={} h={} y={} power={} rotor={} pitch={} roll={} vy={} spd={} target={} reloc={} toHome={}",
                entity.uuid, phase, "%.1f".format(height), "%.1f".format(heli.y),
                "%.4f".format(heli.power), "%.4f".format(heli.synchedPropellerRot),
                "%.1f".format(heli.xRot), "%.1f".format(heli.roll),
                "%.3f".format(heli.deltaMovement.y), "%.3f".format(heli.deltaMovement.horizontalDistance()),
                target?.name?.string ?: "none", relocating,
                "%.1f".format(HelicopterFlightController.horizontalDistance(heli.position(), home))
            )
        }

        when (phase) {
            Phase.TAKEOFF -> {
                if (!wantsToFly && height < MIN_TRANSLATE_HEIGHT) {
                    idleOnPad(heli, rollRate)
                    return
                }
                // Straight up first: translating at rooftop height is how you fly into a hill.
                val safeY = groundY(level, heli.x, heli.z) + CRUISE_CLEARANCE
                climbStraight(heli, safeY, rollRate)
                if (heli.y >= safeY - 1.0) {
                    phase = Phase.TRANSIT
                    DebugFlags.log("[heli-debug] {} airborne, transiting to {}", entity.uuid, station)
                }
            }
            Phase.TRANSIT -> {
                fly(level, heli, station, facing, mission.speed, rollRate)
                if (HelicopterFlightController.horizontalDistance(heli.position(), station) <= ON_STATION_RADIUS) {
                    onStationReached(mission)
                    phase = if (mission.landOnArrival) Phase.LANDING else Phase.STATION
                }
            }
            Phase.STATION -> {
                fly(level, heli, station, facing, minOf(mission.speed, HOLD_SPEED), rollRate)
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
        val anchor: Vec3 = station
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
        if (!airworthy) return Mission(retirePoint(home), null, true, CRUISE_SPEED)

        if (target != null) {
            return Mission(standoffPoint(heli, target.position()), target.position(), false, HOLD_SPEED)
        }
        if (stillInContact(entity)) {
            val contact = lastContactPos ?: home
            return Mission(standoffPoint(heli, contact), contact, false, HOLD_SPEED)
        }

        return when (entity.currentSquad()?.order) {
            SquadOrder.DEFEND, SquadOrder.PATROL ->
                Mission(loiterPoint(home), null, false, LOITER_SPEED, anchor = home)
            SquadOrder.ATTACK -> Mission(retirePoint(home), null, true, CRUISE_SPEED)
            else -> Mission(home, null, true, CRUISE_SPEED)
        }
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
        level: ServerLevel, heli: VehicleEntity, station: Vec3, facing: Vec3?, speed: Double, rollRate: Float
    ) {
        val desiredY = DroneFlightController.cruiseAltitude(
            terrainAhead(level, heli, station), CRUISE_CLEARANCE, heli.y
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

    private fun tuningOf(heli: VehicleEntity): HelicopterFlightController.Tuning {
        val engine = heli.computed().engineInfo as? EngineInfo.Helicopter
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
        const val CRUISE_SPEED = 0.85
        const val HOLD_SPEED = 0.15
        const val STANDOFF_RANGE = 42.0
        /** How far the squad's objective has to be before flying there beats sitting on the pad. */
        const val RELOCATE_DISTANCE = 24.0
        /** Clear this far from the last fight before putting down. */
        const val RETIRE_DISTANCE = 80.0
        /** Keep treating the area as hot for this long after the last confirmed target. */
        const val COMBAT_HOLD_TICKS = 200
        const val LOITER_RADIUS = 70.0
        const val LOITER_SPEED = 0.35
        const val LOITER_STEP = Math.PI / 4
        const val ON_STATION_RADIUS = 8.0
        const val OFF_STATION_RADIUS = 20.0
        const val DESCENT_RATE = 0.22
        /** Break off far above SBW's 10% "controls gone" threshold. */
        const val RETREAT_HEALTH_FRACTION = 0.35f
        const val MIN_RESERVE_ENERGY = 200_000
        val LOOKAHEAD_DISTANCES = listOf(12.0, 24.0, 40.0, 60.0)
    }
}

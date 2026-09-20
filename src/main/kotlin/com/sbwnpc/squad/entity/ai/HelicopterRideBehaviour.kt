package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.item.gun.GunItem

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DroneCombat
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Rides a squad's transport helicopter: board it on the pad, sit still for the trip, get out once
 * it is down at the far end.
 *
 * The one rule that matters more than any other here is that a passenger never leaves the aircraft
 * in the air. Everything else about this behaviour can misfire and cost a wasted walk; dismounting
 * at cruise altitude costs the squad.
 *
 * Only worth doing at range — closer than [Helicopters.AIR_TRANSPORT_DISTANCE] the squad walks,
 * which is what the ground-vehicle equivalent ([VehicleTransportBehaviour]) already decides for
 * itself. Boarding needs the aircraft landed and a pilot already in seat 0: SBW hands the controls
 * to whoever the first passenger is, so an empty gunship filling up with riflemen would leave one
 * of them nominally flying it.
 */
class HelicopterRideBehaviour : ExtendedBehaviour<NpcEntity>() {
    private enum class Phase { BOARDING, RIDING }

    private var phase = Phase.BOARDING
    private var rideId: UUID? = null
    private var nextSearchTick = 0
    private var groundedSince = Int.MIN_VALUE / 2
    private var noLiftUntilTick = 0
    private var nextShotTick = 0

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        canRide(entity) && (aboard(entity) != null || lift(entity, level) != null)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        if (aboard(entity) != null) return true
        val level = entity.level() as? ServerLevel ?: return false
        return canRide(entity) && lift(entity, level) != null
    }

    override fun start(entity: NpcEntity) {
        phase = if (aboard(entity) != null) Phase.RIDING else Phase.BOARDING
        groundedSince = Int.MIN_VALUE / 2
    }

    override fun stop(entity: NpcEntity) {
        rideId = null
        entity.vehicleTransport = false
        entity.navigation.stop()
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val riding = aboard(entity)
        if (riding != null) {
            phase = Phase.RIDING
            ride(entity, riding)
            return
        }
        phase = Phase.BOARDING
        board(entity, level)
    }

    private fun ride(entity: NpcEntity, heli: VehicleEntity) {
        entity.vehicleTransport = true
        entity.navigation.stop()
        rideId = heli.uuid
        fireFromBench(entity, heli)

        // Never in the air, whatever else is true — including a wrecked airframe. SBW crashes the
        // passengers of a destroyed aircraft itself (DestroyInfo.CrashPassengers); stepping out at
        // altitude first only adds a fall to it.
        val level = entity.level() as? ServerLevel ?: return
        if (!Helicopters.isGrounded(level, heli)) {
            groundedSince = Int.MIN_VALUE / 2
            return
        }
        if (!heli.isAlive || heli.isWreck) {
            entity.stopRiding()
            entity.vehicleTransport = false
            return
        }
        if (groundedSince == Int.MIN_VALUE / 2) groundedSince = entity.tickCount

        val home = entity.homeCenter()
        val arrived = home != null &&
            HelicopterFlightController.horizontalDistance(entity.position(), home) <= DISMOUNT_RADIUS
        // Either this is the drop-off, or the lift has plainly ended somewhere else — a pilot that
        // set down short (damaged, out of power) shouldn't leave its passengers sitting in a
        // parked aircraft forever.
        if (arrived || entity.tickCount - groundedSince > STRANDED_TICKS) {
            entity.stopRiding()
            entity.vehicleTransport = false
            rideId = null
            // Walk away properly before considering another lift. Without this, anyone put down
            // short of the objective would turn straight round and climb back into the aircraft
            // that had just given up on the trip.
            noLiftUntilTick = entity.tickCount + REBOARD_COOLDOWN_TICKS
            DebugFlags.log("[heli-debug] {} dismounted at {}", entity.uuid, entity.position())
        }
    }

    private fun board(entity: NpcEntity, level: ServerLevel) {
        val heli = lift(entity, level) ?: return
        entity.vehicleTransport = true
        val hull = heli.boundingBox
        if (hull.distanceToSqr(entity.position()) > BOARD_DISTANCE_SQR) {
            entity.navigateTo(
                entity.x.coerceIn(hull.minX, hull.maxX),
                entity.y.coerceIn(hull.minY, hull.maxY),
                entity.z.coerceIn(hull.minZ, hull.maxZ),
                BOARD_SPEED
            )
            return
        }
        entity.navigation.stop()
        if (entity.startRiding(heli, false)) {
            phase = Phase.RIDING
            groundedSince = Int.MIN_VALUE / 2
            DebugFlags.log("[heli-debug] {} boarded transport {}", entity.uuid, heli.uuid)
        }
    }

    /**
     * Door guns, near enough. SBW already fires a seat's own weapon for any mob with a target that
     * has the barrel lined up — but the AH-6's bench seats have no weapon of their own, so the
     * riders shoot what they are carrying.
     *
     * [GunAttackBehaviour] can't do it for them: riding sets `vehicleTransport`, which is exactly
     * what tells it to stand down, and it would also try to walk them into a firing position.
     */
    private fun fireFromBench(entity: NpcEntity, heli: VehicleEntity) {
        if (heli.getSeatIndex(entity) < FIRST_BENCH_SEAT) return
        val target = entity.target?.takeIf { it.isAlive && SquadTeams.isHostile(entity, it) } ?: return
        if (entity.mainHandItem.item !is GunItem) return
        val gun = GunData.from(entity.mainHandItem)

        gun.tick(entity, true)
        if (gun.shouldStartReloading(entity)) gun.startReload()
        if (gun.shouldStartBolt()) gun.startBolt()

        val aim = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
        entity.lookAt(EntityAnchorArgument.Anchor.EYES, aim)
        if (entity.distanceTo(target) > BENCH_RANGE) return
        if (!entity.sensing.hasLineOfSight(target)) return
        if (entity.tickCount < nextShotTick || !gun.canShoot(entity)) return

        val spread = DroneCombat.spreadForTarget(entity.npcRank.spread * entity.npcClass.accuracyMultiplier, target)
        if (!FriendlyFireGuard.hasClearLineOfFire(entity, aim, spread)) return

        gun.shoot(entity, spread, false, null, aim)
        entity.lastShotTick = entity.tickCount
        var cooldown = (1200.0 / gun.get(GunProp.RPM).toDouble().coerceAtLeast(1.0)).roundToInt().coerceAtLeast(1)
        val mode = gun.selectedFireModeInfo().mode
        if (mode == FireMode.SEMI || (mode == FireMode.BURST && gun.burstAmount.get() == 0)) {
            cooldown += (entity.npcRank.semiFireIntervalMs / 50).toInt()
        }
        nextShotTick = entity.tickCount + cooldown
    }

    private fun aboard(entity: NpcEntity): VehicleEntity? =
        (entity.vehicle as? VehicleEntity)?.takeIf { Helicopters.isHelicopter(it) && it.getSeatIndex(entity) != PILOT_SEAT }

    /** Ordinary infantry only: every other class has a post of its own to man. */
    private fun canRide(entity: NpcEntity): Boolean = when (entity.npcClass) {
        NpcClass.RIFLEMAN, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC -> true
        NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER, NpcClass.TANK_CREW,
        NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER, NpcClass.DRONE_OPERATOR -> false
    }

    private fun boardable(heli: VehicleEntity, entity: NpcEntity): Boolean = reject(heli, entity) == null

    /** Why this aircraft is no good, or null if it will do. Phrased as the reason rather than a
     *  bare boolean so a transport that nobody boards can say so in the log. */
    private fun reject(heli: VehicleEntity, entity: NpcEntity): String? {
        val level = entity.level() as? ServerLevel ?: return "no level"
        if (!Helicopters.isHelicopter(heli) || !heli.isAlive || heli.isWreck) return "not a live helicopter"
        if (heli.locked) return "locked"
        // Transports only. The gunship's one spare seat is its turret, and a rifleman sitting in it
        // is a rifleman keeping the gunner out of it.
        if (Helicopters.hasTurret(heli)) return "gunship"
        if (!Helicopters.isGrounded(level, heli)) return "still airborne"
        if (SquadTeams.factionOf(heli) != SquadTeams.factionOf(entity)) return "wrong faction"
        val seats = heli.getOrderedPassengers()
        // A pilot must already hold seat 0 — see the class doc.
        if (seats.getOrNull(PILOT_SEAT) == null) return "no pilot aboard"
        if (seats.drop(1).none { it == null }) return "full"
        return null
    }

    /** Everything except actually finding an aircraft: cheap, unthrottled, and safe to ask every
     *  tick. */
    private fun wantsLift(entity: NpcEntity): Boolean {
        if (entity.tickCount < noLiftUntilTick) return false
        if (entity.vehicle != null || entity.diggedIn || entity.operatingDrone || entity.antiDroneEngaged) return false
        if (entity.target != null || entity.isSuppressed()) return false
        val home = entity.homeCenter() ?: return false
        return entity.position().distanceTo(home) >= Helicopters.AIR_TRANSPORT_DISTANCE
    }

    /**
     * The transport this NPC is headed for: the one already chosen while it still checks out,
     * otherwise a fresh look around.
     *
     * The chosen aircraft is remembered rather than re-found, because this is asked from three
     * places — "may I start", "should I keep running", and the walking itself — and the scan is
     * rate-limited. Without the cache the eligibility check consumed each scan and the walking
     * leg got a throttled null, so the behaviour started and stopped every couple of seconds
     * without ever taking a step.
     */
    private fun lift(entity: NpcEntity, level: ServerLevel): VehicleEntity? {
        // Asked before the remembered aircraft, not after. The other way round, a squad dropped at
        // its objective still had a perfectly boardable transport parked next to it, so the ride
        // kept hold of them — and this behaviour running is what tells the squad's ordinary orders
        // to stand down. They were "waiting for a lift" they no longer needed.
        if (!wantsLift(entity)) {
            rideId = null
            return null
        }
        rideId?.let { id ->
            val known = level.getEntity(id) as? VehicleEntity
            if (known != null && boardable(known, entity)) return known
            rideId = null
        }
        if (entity.tickCount < nextSearchTick) return null
        nextSearchTick = entity.tickCount + SEARCH_INTERVAL_TICKS

        val box = AABB.ofSize(entity.position(), SEARCH_RANGE * 2, SEARCH_RANGE * 2, SEARCH_RANGE * 2)
        val nearby = level.getEntitiesOfClass(VehicleEntity::class.java, box)
            .filter { Helicopters.isHelicopter(it) }
            .sortedBy { entity.distanceToSqr(it) }
        val chosen = nearby.firstOrNull { boardable(it, entity) }
        if (chosen == null) {
            if (nearby.isNotEmpty()) {
                DebugFlags.log(
                    "[heli-debug] {} found no ride: {}", entity.uuid,
                    nearby.joinToString { "${it.uuid}=${reject(it, entity)}" }
                )
            }
            return null
        }
        rideId = chosen.uuid
        DebugFlags.log("[heli-debug] {} heading for transport {}", entity.uuid, chosen.uuid)
        return chosen
    }

    private companion object {
        const val PILOT_SEAT = 0
        const val SEARCH_RANGE = 40.0
        const val SEARCH_INTERVAL_TICKS = 40
        const val BOARD_DISTANCE_SQR = 2.5 * 2.5
        const val BOARD_SPEED = 1.0
        const val DISMOUNT_RADIUS = 32.0
        /** Grounded this long somewhere that isn't the objective: get out and walk. */
        const val STRANDED_TICKS = 200
        const val REBOARD_COOLDOWN_TICKS = 200
        /** Seats 0 and 1 are the cockpit; the side benches start here. */
        const val FIRST_BENCH_SEAT = 2
        const val BENCH_RANGE = 48.0
    }
}

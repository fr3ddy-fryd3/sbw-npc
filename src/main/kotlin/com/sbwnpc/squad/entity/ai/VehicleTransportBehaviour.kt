package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.mojang.datafixers.util.Pair
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
 *  - DRIVING (driver only): steering toward the squad's home point.
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
    private var repathCooldown = 0
    private var nextSeekTick = 0

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun combatInterrupted(entity: NpcEntity) =
        entity.target != null || entity.isAlert() || entity.isSuppressed()

    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.vehicle != null) return true // already mounted: DRIVING/RIDING keep going regardless
        if (combatInterrupted(entity)) return false
        if (entity.diggedIn) return false
        val squad = entity.currentSquad() ?: return false
        if (squad.order == SquadOrder.FREE) return false
        val home = entity.homeCenter() ?: return false
        return entity.position().distanceTo(home) > TRANSPORT_DISTANCE_THRESHOLD
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = eligible(entity)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean =
        if (entity.vehicle != null) true else eligible(entity)

    override fun start(entity: NpcEntity) {
        phase = Phase.SEEKING
        targetVehicleId = null
        repathCooldown = 0
        nextSeekTick = 0
        entity.vehicleTransport = true
    }

    override fun stop(entity: NpcEntity) {
        VehicleTransportClaims.release(entity.uuid)
        entity.vehicleTransport = false
        phase = Phase.SEEKING
        targetVehicleId = null
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
            }
            return
        }

        val candidate = level.getEntitiesOfClass(
            VehicleEntity::class.java,
            AABB.ofSize(entity.position(), SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2)
        ).filter {
            it.isAlive && it.maxPassengers > 0 && VehicleTransportClaims.claimedSeats(it.uuid) == 0 &&
                it.passengers.isEmpty()
        }.minByOrNull { entity.distanceToSqr(it) } ?: return

        if (VehicleTransportClaims.claimDriver(candidate.uuid, entity.uuid)) {
            targetVehicleId = candidate.uuid
            phase = Phase.BOARDING
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
            return
        }

        if (entity.distanceToSqr(vehicle) > BOARD_DISTANCE * BOARD_DISTANCE) {
            if (repathCooldown == 0) {
                entity.navigation.moveTo(vehicle.x, vehicle.y, vehicle.z, RUN_SPEED_MODIFIER)
                repathCooldown = REPATH_INTERVAL_TICKS
            }
            return
        }

        entity.navigation.stop()
        // Not force=true: lets VehicleEntity.canAddPassenger's own real seat-capacity check apply as
        // a final backstop, instead of only trusting the app-level claim registry.
        if (!entity.startRiding(vehicle, false)) return

        entity.currentSquad()?.faction?.let { SquadTeams.assign(vehicle, it) }

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
        if (allAccountedFor || timedOut) phase = Phase.DRIVING
    }

    private fun tickDriving(entity: NpcEntity) {
        val vehicle = mountOrAbort(entity) ?: return
        val home = entity.homeCenter()
        if (home == null) {
            entity.stopRiding()
            return
        }

        if (vehicle.position().distanceTo(home) <= ARRIVAL_RADIUS) {
            stopVehicle(vehicle)
            entity.stopRiding()
            return
        }

        steerToward(vehicle, home)
    }

    /** Passenger only: no driving of its own — just watches for the vehicle actually getting close
     *  enough and dismounts itself, independently of what the driver's own instance is doing. */
    private fun tickRiding(entity: NpcEntity) {
        val vehicle = mountOrAbort(entity) ?: return
        val home = entity.homeCenter() ?: run { entity.stopRiding(); return }
        if (vehicle.position().distanceTo(home) <= ARRIVAL_RADIUS) entity.stopRiding()
    }

    /** Simple steer-toward-point: always throttle forward, turn left/right to close the heading gap.
     *  Sign of the turn inputs is derived from VehicleEngineUtils' own steering math (rightInputDown
     *  increases yRot, leftInputDown decreases it). VehicleVecUtils.getYRotFromVector's raw output is
     *  the negation of yRot's own convention — every other call site in SuperbWarfare that compares
     *  it against yRot negates it first (e.g. VehicleEntity.updateRotation) — so it's negated here too. */
    private fun steerToward(vehicle: VehicleEntity, target: Vec3) {
        val toTarget = target.subtract(vehicle.position())
        val desiredYaw: Double = -VehicleVecUtils.getYRotFromVector(toTarget)
        val diff = Mth.wrapDegrees(desiredYaw - vehicle.yRot.toDouble())

        vehicle.forwardInputDown = true
        vehicle.backInputDown = false
        vehicle.rightInputDown = diff > TURN_DEADZONE_DEGREES
        vehicle.leftInputDown = diff < -TURN_DEADZONE_DEGREES
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
        private const val REPATH_INTERVAL_TICKS = 20
        private const val TURN_DEADZONE_DEGREES = 6.0
        private const val RUN_SPEED_MODIFIER = 1.0
    }
}

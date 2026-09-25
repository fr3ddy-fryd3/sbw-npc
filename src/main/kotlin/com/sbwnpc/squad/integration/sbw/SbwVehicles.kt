package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.init.ModEntities
import com.atsuishio.superbwarfare.init.ModItems
import com.sbwnpc.squad.domain.port.CannonRound
import com.sbwnpc.squad.domain.port.Mobility
import com.sbwnpc.squad.domain.port.Steering
import com.sbwnpc.squad.domain.port.VehicleModel
import com.sbwnpc.squad.domain.port.Vehicles
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object SbwVehicles : Vehicles {
    /** `VehicleEntity.engineInfo` is deserialized lazily, on the vehicle's first engine tick, so a
     *  vehicle nobody has driven yet has none to read. Mid-range of what the ground vehicles
     *  actually draw (64 for a LAV, 128 for a T-90). */
    private const val ASSUMED_COST_RATE = 96.0

    private val types: Map<VehicleModel, () -> EntityType<*>> = mapOf(
        TankModel.ZTZ_99A to { ModEntities.ZTZ_99A.get() },
        TankModel.T_90A to { ModEntities.T_90A.get() },
        TankModel.M1A2 to { ModEntities.M_1A_2.get() },
        HelicopterModel.MI_28 to { ModEntities.MI_28.get() },
        HelicopterModel.AH_6 to { ModEntities.AH_6.get() },
        TransportVehicle.LAV_25 to { ModEntities.LAV_25.get() },
        TransportVehicle.LAV_150 to { ModEntities.LAV_150.get() },
        TransportVehicle.BMP_2 to { ModEntities.BMP_2.get() },
    )

    override fun isVehicle(entity: Entity?): Boolean = entity is VehicleEntity

    override fun isOperational(entity: Entity?): Boolean =
        entity is VehicleEntity && entity.isAlive && !entity.isWreck

    override fun isWreck(vehicle: Entity): Boolean = vehicle is VehicleEntity && vehicle.isWreck

    override fun isLocked(vehicle: Entity): Boolean = vehicle is VehicleEntity && vehicle.locked

    override fun seatCount(vehicle: Entity): Int = (vehicle as? VehicleEntity)?.maxPassengers ?: 0

    override fun seatOf(vehicle: Entity, passenger: Entity): Int =
        (vehicle as? VehicleEntity)?.getSeatIndex(passenger) ?: -1

    override fun seating(vehicle: Entity): List<Entity?> =
        (vehicle as? VehicleEntity)?.getOrderedPassengers() ?: emptyList()

    override fun hasWeaponAt(vehicle: Entity, passenger: Entity): Boolean =
        vehicle is VehicleEntity && vehicle.getGunData(passenger) != null

    // SBW's engines simply stop responding when the battery runs down — `VehicleEngineUtils`
    // zeroes every input once `energy <= energyCost` — with no outward sign beyond the vehicle not
    // moving. Measured in ticks of driving because the vehicles differ by a factor of two in what
    // they draw (`EnergyCostRate` 64 for a LAV, 128 for a T-90).
    override fun hasPowerFor(vehicle: Entity, ticks: Double): Boolean {
        if (vehicle !is VehicleEntity) return false
        // Not everything runs on a battery — the mortar's MaxEnergy is 0, and nothing with no
        // energy storage can ever be out of power.
        if (!vehicle.hasEnergyStorage()) return true
        val perTick = vehicle.engineInfo?.energyCostRate ?: ASSUMED_COST_RATE
        return vehicle.energy > perTick * ticks
    }

    override fun modelOf(vehicle: Entity): VehicleModel? =
        if (vehicle !is VehicleEntity) null else types.entries.firstOrNull { it.value() == vehicle.type }?.key

    override fun create(level: ServerLevel, model: VehicleModel): Entity? =
        types[model]?.invoke()?.create(level) as? VehicleEntity

    override fun fuelAndArm(vehicle: Entity, model: VehicleModel) {
        if (vehicle !is VehicleEntity) return
        vehicle.energy = vehicle.maxEnergy
        when (model) {
            // Same main-gun AP/HE + coax rifle ammo + .50cal passenger ammo loadout for all three —
            // verified against each model's own sbw/vehicles/*.json: same four weapon/ammo slots.
            is TankModel -> {
                vehicle.setItem(0, ItemStack(ModItems.LARGE_SHELL_AP.get(), 64))
                vehicle.setItem(1, ItemStack(ModItems.LARGE_SHELL_HE.get(), 64))
                vehicle.setItem(2, ItemStack(ModItems.RIFLE_AMMO.get(), 64))
                vehicle.setItem(3, ItemStack(ModItems.HEAVY_AMMO.get(), 64))
            }
            // Cannon rounds with AP first and HE second — the order VehicleCannonAmmo assumes — plus
            // rockets. The AH-6's 20mm only takes HE, so it gets no AP stack.
            HelicopterModel.MI_28 -> {
                vehicle.setItem(0, ItemStack(ModItems.SMALL_SHELL_AP.get(), 64))
                vehicle.setItem(1, ItemStack(ModItems.SMALL_SHELL_HE.get(), 64))
                vehicle.setItem(2, ItemStack(ModItems.SMALL_ROCKET.get(), 16))
            }
            HelicopterModel.AH_6 -> {
                vehicle.setItem(0, ItemStack(ModItems.SMALL_SHELL_HE.get(), 64))
                vehicle.setItem(1, ItemStack(ModItems.SMALL_ROCKET.get(), 16))
            }
            // Small-caliber AP only, per user call — a short stack, not the full loadout the tanks get.
            is TransportVehicle -> vehicle.setItem(0, ItemStack(ModItems.SMALL_SHELL_AP.get(), 4))
            else -> {}
        }
    }

    // SBW turns a vehicle at zero health into a wreck on its next tick and detonates it on contact.
    override fun writeOff(vehicle: Entity) {
        (vehicle as? VehicleEntity)?.health = 0f
    }

    override fun within(level: Level, area: AABB, filter: (Entity) -> Boolean): List<Entity> =
        level.getEntitiesOfClass(VehicleEntity::class.java, area) { filter(it) }

    // --- Driving ---

    private val GROUND_ENGINES = setOf(EngineType.WHEEL, EngineType.TRACK, EngineType.WHEELCHAIR)

    override fun mobility(vehicle: Entity): Mobility? {
        if (vehicle !is VehicleEntity) return null
        return when (vehicle.computed().engineType) {
            in GROUND_ENGINES -> Mobility.GROUND
            EngineType.HELICOPTER, EngineType.AIRCRAFT -> Mobility.AIR
            EngineType.SHIP -> Mobility.WATER
            else -> Mobility.FIXED
        }
    }

    /**
     * Always throttle forward, turn left/right to close the heading gap.
     *
     * Targets [VehicleEntity.rudderRot] directly — SBW's own "steering wheel" state
     * (VehicleEngineUtils.wheelEngine) — rather than reacting to raw heading error with
     * hysteresis/timers: rightInputDown drives rudderRot negative, leftInputDown drives it positive,
     * and it decays 25%/tick UNCONDITIONALLY (even while held), so [MAX_RUDDER_MAGNITUDE] targets well
     * under the hard ±0.8 clamp rather than up against it (see its own comment). yRot's rate of change
     * per tick is `-12 * speed * rudderRot * sign(power)`.
     *
     * `targetRudder = clamp(-diff / RUDDER_FULL_LOCK_DEGREES, -1, 1) * MAX_RUDDER_MAGNITUDE` (negative
     * because turning right — wanted when diff > 0 — drives rudderRot negative). Hold whichever input
     * direction closes the gap between actual rudderRot and that target; release within
     * [RUDDER_DEADBAND] of it. Holding one direction at speed both rotates and translates the vehicle,
     * so a fixed hold duration risks a stable circular orbit; targeting a shrinking rudderRot avoids
     * that structurally, since the commanded turn backs off as the vehicle actually straightens out
     * rather than staying locked in until a timer expires.
     */
    override fun driveToward(vehicle: Entity, point: Vec3): Steering {
        if (vehicle !is VehicleEntity) return Steering(right = false, left = false, detail = "not a vehicle")
        val toTarget = point.subtract(vehicle.position())
        // VehicleVecUtils.getYRotFromVector's raw output is the negation of yRot's own convention —
        // every other call site in SuperbWarfare that compares it against yRot negates it first (e.g.
        // VehicleEntity.updateRotation) — so it's negated here too.
        val desiredYaw: Double = -VehicleVecUtils.getYRotFromVector(toTarget)
        val diff = Mth.wrapDegrees(desiredYaw - vehicle.yRot.toDouble())

        val targetRudder = Mth.clamp((-diff / RUDDER_FULL_LOCK_DEGREES).toFloat(), -1f, 1f) * MAX_RUDDER_MAGNITUDE
        val rudderError = vehicle.rudderRot - targetRudder
        val right = rudderError > RUDDER_DEADBAND
        val left = rudderError < -RUDDER_DEADBAND

        vehicle.forwardInputDown = true
        vehicle.backInputDown = false
        vehicle.rightInputDown = right
        vehicle.leftInputDown = left
        return Steering(
            right, left,
            "pos=${vehicle.position()} yRot=${vehicle.yRot} desiredYaw=$desiredYaw diff=$diff " +
                "rudderRot=${vehicle.rudderRot} targetRudder=$targetRudder speed=${vehicle.deltaMovement.horizontalDistance()}"
        )
    }

    override fun reverse(vehicle: Entity, turnLeft: Boolean) {
        if (vehicle !is VehicleEntity) return
        vehicle.forwardInputDown = false
        vehicle.backInputDown = true
        vehicle.leftInputDown = turnLeft
        vehicle.rightInputDown = !turnLeft
    }

    override fun release(vehicle: Entity) {
        if (vehicle !is VehicleEntity) return
        vehicle.forwardInputDown = false
        vehicle.backInputDown = false
        vehicle.leftInputDown = false
        vehicle.rightInputDown = false
    }

    // Releasing the pedals alone does not slow a wheeled vehicle down fast enough: per
    // VehicleEngineUtils.wheelEngine, `power` (throttle) only decays 3%/tick when idle and keeps
    // adding forward thrust every tick proportional to itself regardless of input state. Zeroing it
    // removes that thrust immediately; ground friction then kills actual speed within a few ticks.
    override fun cutPower(vehicle: Entity) {
        (vehicle as? VehicleEntity)?.power = 0f
    }

    override fun hull(vehicle: Entity): AABB =
        (vehicle as? VehicleEntity)?.getCombinedAABB() ?: vehicle.boundingBox

    override fun wouldHit(vehicle: Entity, other: Entity, offset: Vec3): Boolean {
        if (vehicle !is VehicleEntity) return vehicle.boundingBox.move(offset).intersects(other.boundingBox)
        return if (vehicle.enableAABB()) vehicle.boundingBox.move(offset).intersects(other.boundingBox)
        else vehicle.isInObb(other, offset)
    }

    // --- Damage ---

    override fun lastHitTime(vehicle: Entity): Long = (vehicle as? VehicleEntity)?.lastDamageStamp ?: 0L

    override fun lastAttacker(vehicle: Entity): Entity? {
        if (vehicle !is VehicleEntity) return null
        return vehicle.lastDamageSource?.entity as? LivingEntity ?: vehicle.lastAttacker as? LivingEntity
    }

    // --- Weapons ---

    override fun canFire(vehicle: Entity, passenger: LivingEntity): Boolean =
        vehicle is VehicleEntity && vehicle.canShoot(passenger)

    override fun seatReady(vehicle: Entity, seat: Int): Boolean =
        vehicle is VehicleEntity && vehicle.getGunData(seat)?.canShoot(vehicle.ammoSupplier) == true

    // `canShoot` is false while SBW reloads or cools a weapon; only an empty weapon counts here.
    override fun seatHasAmmo(vehicle: Entity, passenger: Entity): Boolean {
        if (vehicle !is VehicleEntity) return false
        val gunData = vehicle.getGunData(passenger) ?: return false
        return gunData.hasEnoughAmmoToShoot(vehicle.ammoSupplier) ||
            gunData.countBackupAmmo(vehicle.ammoSupplier) > 0
    }

    // SBW only learns about a target it did not see acquired itself through these UUIDs — e.g. one
    // the gunner already had while still walking up to the vehicle.
    override fun aimAt(vehicle: Entity, gunner: Entity, target: LivingEntity?) {
        if (vehicle !is VehicleEntity) return
        val targetId = target?.stringUUID ?: "undefined"
        if (gunner === vehicle.getNthEntity(vehicle.turretControllerIndex)) {
            vehicle.aiTurretTargetUUID = targetId
        }
        if (gunner === vehicle.getNthEntity(vehicle.passengerWeaponStationControllerIndex)) {
            vehicle.aiPassengerWeaponTargetUUID = targetId
        }
    }

    // Every crewed SBW cannon the mod uses lists AP first and HE second in its `AmmoType` — the three
    // tanks and the Mi-28's 30mm turret.
    override fun loadRound(vehicle: Entity, seat: Int, weapon: Int, round: CannonRound) {
        if (vehicle !is VehicleEntity) return
        val ammo = when (round) {
            CannonRound.ARMOUR_PIERCING -> 0
            CannonRound.HIGH_EXPLOSIVE -> 1
        }
        if (vehicle.getWeaponIndex(seat) != weapon) {
            vehicle.setWeaponIndex(seat, weapon)
        }
        vehicle.modifyGunData(seat, weapon) { gun ->
            if (gun.selectedAmmoType.get() != ammo) {
                gun.changeAmmoConsumer(ammo, vehicle.ammoSupplier)
            }
        }
    }

    // Heading error (degrees) at/beyond which driveToward commands full steering lock
    // (MAX_RUDDER_MAGNITUDE). Smaller = more aggressive (reaches full lock at a gentler heading
    // error); 45° means anything from a moderate correction to a near-reversal all command close to
    // full lock, while small corrections near the target heading get proportionally gentle steering.
    private const val RUDDER_FULL_LOCK_DEGREES = 45.0
    // Below the vehicle's hard rudderRot clamp (±0.8 in VehicleEngineUtils.wheelEngine) on purpose:
    // rudderRot's *0.75 decay applies every tick even while an input is held, so continuous
    // single-direction holding only settles at ~0.3-0.6 depending on speed (lower at cruising
    // speed, since deltaRot's own decay also scales with speed) — well under the hard clamp.
    // Targeting within that achievable range keeps the proportional response effective across most
    // of a turn, not just its final stretch.
    private const val MAX_RUDDER_MAGNITUDE = 0.4f
    // How close actual rudderRot must get to the target before releasing input — too small and
    // float noise/the engine's own per-tick rudderRot changes chatter the input on/off every tick;
    // too large and steering stays visibly short of what was actually commanded.
    private const val RUDDER_DEADBAND = 0.05f
}

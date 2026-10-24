package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.config.server.VehicleConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.tools.RangeTool
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Sightline
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d

/** A usable body shot through the native muzzle, ballistic solution and station limits. */
object VehicleAim {
    private const val ANGLE_EPSILON = 1e-3

    private data class Station(val pitch: Float, val yaw: Float, val vector: Vec3, val minPitch: Float, val maxPitch: Float, val minYaw: Float, val maxYaw: Float) {
        fun permits(solution: Vec3): Boolean {
            if (!solution.lengthSqr().isFinite() || solution.lengthSqr() == 0.0) return false
            val requestedPitch = pitch + Mth.wrapDegrees(-VehicleVecUtils.getXRotFromVector(solution) + VehicleVecUtils.getXRotFromVector(vector))
            val requestedYaw = Mth.wrapDegrees(yaw - Mth.wrapDegrees(-VehicleVecUtils.getYRotFromVector(solution) + VehicleVecUtils.getYRotFromVector(vector)))
            return requestedPitch in (minPitch - ANGLE_EPSILON)..(maxPitch + ANGLE_EPSILON) &&
                requestedYaw in (minYaw - ANGLE_EPSILON)..(maxYaw + ANGLE_EPSILON)
        }
    }

    /** Previews an empty [seat] as well as checking an already mounted [gunner], without moving the gun. */
    @JvmStatic
    fun canEngage(vehicle: VehicleEntity, gunner: Entity, seat: Int, target: LivingEntity): Boolean {
        val muzzle = muzzle(vehicle, seat) ?: return false
        val level = vehicle.level() as? ServerLevel ?: return false
        val body = target.vehicle ?: target
        val aim = body.boundingBox.center
        if (!DetectionSightline.visible(level, muzzle, aim, gunner) || Sightline.blocked(level, muzzle, aim, gunner)) return false
        return withinLimits(vehicle, gunner, seat, body, muzzle)
    }

    /**
     * Whether the gun at [seat] could be laid on [target] at all, whatever stands between: the
     * station limits and a ballistic solution, without the muzzle sightline [canEngage] adds.
     */
    @JvmStatic
    fun canReach(vehicle: VehicleEntity, gunner: Entity, seat: Int, target: LivingEntity): Boolean {
        val muzzle = muzzle(vehicle, seat) ?: return false
        return withinLimits(vehicle, gunner, seat, target.vehicle ?: target, muzzle)
    }

    // The seat-index overload goes through its occupant and falls back to the hull when empty.
    private fun muzzle(vehicle: VehicleEntity, seat: Int): Vec3? {
        if (seat < 0) return null
        val data = vehicle.getGunData(seat) ?: return null
        val weapon = vehicle.gunDataMap.entries.firstOrNull { it.value === data }?.key ?: return null
        return vehicle.getShootPos(weapon, 1f)
    }

    private fun withinLimits(vehicle: VehicleEntity, gunner: Entity, seat: Int, body: Entity, muzzle: Vec3): Boolean {
        val aim = body.boundingBox.center
        // Fixed pilot weapons are aimed with the airframe, not these turret limits.
        val station = station(vehicle, seat) ?: return true
        val velocity = targetVelocity(body)
        val rider = riderPosition(vehicle, gunner, seat) ?: return false
        val barrel = if (gunner.vehicle === vehicle) vehicle.getShootVec(gunner, 1f) else station.vector
        // Match VehicleWeaponUtils' origin behind the muzzle by the rider-to-muzzle distance.
        val origin = muzzle.subtract(barrel.scale(muzzle.distanceTo(rider)))
        val solution = RangeTool.calculateFiringSolution(origin, aim, velocity, vehicle.getProjectileVelocity(seat).toDouble(), vehicle.getProjectileGravity(seat).toDouble()) ?: return false
        return station.permits(solution)
    }

    private fun station(vehicle: VehicleEntity, seat: Int): Station? =
        when (seat) {
            vehicle.turretControllerIndex -> Station(
                vehicle.turretXRot,
                vehicle.turretYRot,
                vehicle.getBarrelVector(1f),
                -vehicle.turretMaxPitch + vehicle.customTurretMaxPitch,
                -vehicle.turretMinPitch - vehicle.customTurretMinPitch,
                -vehicle.turretMaxYaw,
                -vehicle.turretMinYaw,
            )

            vehicle.passengerWeaponStationControllerIndex -> Station(
                vehicle.gunXRot,
                vehicle.gunYRot,
                vehicle.getPassengerWeaponStationVector(1f),
                -vehicle.passengerWeaponMaxPitch,
                -vehicle.passengerWeaponMinPitch,
                -vehicle.passengerWeaponMaxYaw,
                -vehicle.passengerWeaponMinYaw,
            )

            else -> passengerStation(vehicle, seat)
        }

    private fun passengerStation(vehicle: VehicleEntity, seat: Int): Station? {
        val direction = vehicle.getGunData(seat)?.fireDirection() ?: return null
        if (!direction.isString || direction.string != "Passenger") return null
        val info = vehicle.getSeat(seat) ?: return null
        // Same frame and orientation as VehicleEntity.clampRotation, including side-facing ports.
        val frame = Matrix4d(vehicle.getTransformFromString(info.transform, 1f)).rotateY(Math.toRadians(-info.orientation.toDouble()))
        val forward = frame.transformDirection(Vector3d(0.0, 0.0, 1.0))
        return Station(0f, 0f, Vec3(forward.x, forward.y, forward.z), info.minPitch, info.maxPitch, -info.maxYaw, -info.minYaw)
    }

    private fun riderPosition(vehicle: VehicleEntity, gunner: Entity, seat: Int): Vec3? {
        if (gunner.vehicle === vehicle) return gunner.position()
        val info = vehicle.getSeat(seat) ?: return null
        val point = info.position
        val at = vehicle.transformPosition(vehicle.getTransformFromString(info.transform, 1f), point.x, point.y, point.z)
        return Vec3(at.x, at.y + VehicleConfig.getPassengerYOffset(gunner.type), at.z)
    }

    private fun targetVelocity(target: Entity): Vec3 {
        val velocity = if (target is LivingEntity) target.deltaMovement.add(0.0, target.getAttributeValue(Attributes.GRAVITY), 0.0) else target.deltaMovement
        return if (target is Player) velocity.multiply(2.0, 1.0, 2.0) else velocity
    }
}

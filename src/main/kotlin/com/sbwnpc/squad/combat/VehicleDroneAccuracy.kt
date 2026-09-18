package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import com.atsuishio.superbwarfare.tools.ProjectileSpreadTool
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/** Keeps the accuracy penalty on individual AI shots, never on a vehicle's saved weapon data. */
object VehicleDroneAccuracy {
    /** Called only at autoAim's vehicleShoot invocation, before SBW queues the delayed shot. */
    @JvmStatic
    fun markAutonomousShot(tower: AutoAimableEntity, position: Vec3?): Vec3? {
        if (position == null) return null
        val target = EntityFindUtil.findEntity(tower.level(), tower.targetUUID)
        val drone = target != null && (DroneCombat.isDrone(target) || DroneCombat.isDrone(target.rootVehicle))
        return AutonomousShotTarget(position, target?.uuid, drone)
    }

    @JvmStatic
    fun adjustShot(parameters: ShootParameters): ShootParameters {
        val autonomous = parameters.targetPos as? AutonomousShotTarget
        if (autonomous != null) {
            return parameters.copy(
                spread = DroneAccuracy.adjustSpread(parameters.spread, autonomous.droneTarget),
                targetPos = autonomous.position()
            )
        }
        val vehicle = parameters.ammoSupplier as? VehicleEntity ?: return parameters
        val shooter = parameters.shooter
        if (shooter !is NpcEntity || shooter.vehicle !== vehicle) return parameters
        val target = npcTarget(vehicle, shooter, parameters)
        val spread = DroneCombat.spreadForTarget(parameters.spread, target)
        return if (spread == parameters.spread) parameters else parameters.copy(spread = spread)
    }

    private fun npcTarget(vehicle: VehicleEntity, shooter: NpcEntity, parameters: ShootParameters): Entity? {
        // Prefer the target captured when this shot was requested, including delayed weapons.
        parameters.targetEntityUUID?.let { parameters.level.getEntity(it)?.let { target -> return target } }
        val seat = vehicle.getSeatIndex(shooter)
        val aimTarget = when (seat) {
            vehicle.turretControllerIndex -> vehicle.aiTurretTargetUUID
            vehicle.passengerWeaponStationControllerIndex -> vehicle.aiPassengerWeaponTargetUUID
            else -> ""
        }
        return EntityFindUtil.findEntity(vehicle.level(), aimTarget) ?: shooter.target
    }

    /**
     * SBW's autonomous lasers bypass projectile spread and damage their target directly. Apply
     * the same cone to their hit test; a missed pulse must still consume its energy and charge.
     * Returning true lets the caller cancel only this missed ray, before target damage/particles.
     */
    @JvmStatic
    fun consumeMissedRay(tower: AutoAimableEntity, shooter: LivingEntity?, target: Entity, data: GunData): Boolean {
        val level = tower.level() as? ServerLevel ?: return false
        if (!tower.active || tower.firstPassenger != null ||
            (!DroneCombat.isDrone(target) && !DroneCombat.isDrone(target.rootVehicle))
        ) return false

        val origin = tower.getShootPos("Main", 1f)
        val direction = ProjectileSpreadTool.generateDirections(
            level.random,
            tower.getShootVec("Main", 1f),
            DroneCombat.spreadForTarget(data.get(GunProp.SPREAD), target),
            1,
            null
        ).first()
        val distance = origin.distanceTo(target.boundingBox.center) + target.boundingBox.size
        val endpoint = origin.add(direction.scale(distance))
        if (target.boundingBox.contains(origin) || target.boundingBox.clip(origin, endpoint).isPresent) return false

        // Do not show SBW's guaranteed-hit beam or impact particles on a missed target.
        tower.laserScale = 0f
        tower.laserLength = 0f
        tower.chargeProgress = 0f
        tower.playShootSound3p(shooter, "Main")
        tower.consumeEnergy(data.get(GunProp.AMMO_COST_PER_SHOOT))
        return true
    }
}

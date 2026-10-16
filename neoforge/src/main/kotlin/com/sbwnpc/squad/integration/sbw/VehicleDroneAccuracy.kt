package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import com.sbwnpc.squad.combat.AutonomousShotTarget
import com.sbwnpc.squad.combat.DroneAccuracy
import com.sbwnpc.squad.combat.DroneCombat
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Keeps the accuracy penalty on individual AI shots, never on a vehicle's saved weapon data.
 *
 * Only [GunData.shoot]'s projectile-spread path is covered — this SBW build's `AutoAimableEntity
 * .rayShoot` is a guaranteed-hit hitscan laser with no spread/miss concept of its own to penalize.
 */
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
}

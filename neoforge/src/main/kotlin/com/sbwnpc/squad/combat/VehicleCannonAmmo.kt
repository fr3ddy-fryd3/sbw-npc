package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.CannonRound
import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * Picks a cannon's round by what it is shooting at: AP into anything riding armour, HE at everyone
 * else. A vehicle is targeted through its occupants, so the check is on the target's own vehicle
 * rather than the target itself.
 *
 * Used for the three tank models (see [TankWeaponSelection]) and the Mi-28's 30mm gunner turret.
 */
object VehicleCannonAmmo {
    fun select(vehicle: Entity, seat: Int, weapon: Int, target: LivingEntity) {
        val round = if (Ports.vehicles.isVehicle(target.vehicle)) CannonRound.ARMOUR_PIERCING else CannonRound.HIGH_EXPLOSIVE
        Ports.vehicles.loadRound(vehicle, seat, weapon, round)
    }
}

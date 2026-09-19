package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.LivingEntity

/**
 * Picks a cannon's round by what it is shooting at: AP into anything riding armour, HE at everyone
 * else. SBW exposes a vehicle target through its occupants, so the check is on the target's own
 * vehicle rather than the target itself.
 *
 * Shared by every crewed SBW cannon that lists AP first and HE second in its `AmmoType` — the three
 * tank models (see [TankWeaponSelection]) and the Mi-28's 30mm gunner turret all use that order.
 */
object VehicleCannonAmmo {
    const val AP = 0
    const val HE = 1

    fun select(vehicle: VehicleEntity, seat: Int, weapon: Int, target: LivingEntity) {
        val ammo = if (target.vehicle is VehicleEntity) AP else HE
        if (vehicle.getWeaponIndex(seat) != weapon) {
            vehicle.setWeaponIndex(seat, weapon)
        }
        vehicle.modifyGunData(seat, weapon) { gun ->
            if (gun.selectedAmmoType.get() != ammo) {
                gun.changeAmmoConsumer(ammo, vehicle.ammoSupplier)
            }
        }
    }
}

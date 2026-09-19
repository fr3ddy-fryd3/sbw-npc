package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.LivingEntity

/** Selects the T-90 cannon round by target type; SBW exposes vehicle targets through their occupants. */
object T90WeaponSelection {
    private const val GUNNER_SEAT = 0
    private const val CANNON_WEAPON = 0
    private const val AP_AMMO = 0
    private const val HE_AMMO = 1

    fun update(gunner: NpcEntity, target: LivingEntity) {
        val vehicle = gunner.vehicle as? VehicleEntity ?: return
        if (vehicle.type != ModEntities.T_90A.get() || vehicle.getSeatIndex(gunner) != GUNNER_SEAT) return

        val ammo = if (target.vehicle is VehicleEntity) AP_AMMO else HE_AMMO
        if (vehicle.getWeaponIndex(GUNNER_SEAT) != CANNON_WEAPON) {
            vehicle.setWeaponIndex(GUNNER_SEAT, CANNON_WEAPON)
        }
        vehicle.modifyGunData(GUNNER_SEAT, CANNON_WEAPON) { gun ->
            if (gun.selectedAmmoType.get() != ammo) {
                gun.changeAmmoConsumer(ammo, vehicle.ammoSupplier)
            }
        }
    }
}

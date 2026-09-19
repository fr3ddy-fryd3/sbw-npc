package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity

/** Selects the main-gun round by target type for any [SquadPreset.T90_CREW]-spawned tank (ZTZ-99A,
 *  T-90A, M1A2 — same Cannon/MachineGun gunner seat and AP/HE ammo ordinal in each model's own
 *  sbw/vehicles JSON); SBW exposes vehicle targets through their occupants. */
object TankWeaponSelection {
    private val TANK_TYPES: Set<EntityType<*>> by lazy {
        setOf(ModEntities.ZTZ_99A.get(), ModEntities.T_90A.get(), ModEntities.M_1A_2.get())
    }
    private const val GUNNER_SEAT = 0
    private const val CANNON_WEAPON = 0

    fun update(gunner: NpcEntity, target: LivingEntity) {
        val vehicle = gunner.vehicle as? VehicleEntity ?: return
        if (vehicle.type !in TANK_TYPES || vehicle.getSeatIndex(gunner) != GUNNER_SEAT) return
        VehicleCannonAmmo.select(vehicle, GUNNER_SEAT, CANNON_WEAPON, target)
    }
}

package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.TankModel
import net.minecraft.world.entity.LivingEntity

/** Selects the main-gun round by target type for any [SquadPreset.T90_CREW]-spawned tank (ZTZ-99A,
 *  T-90A, M1A2 — same Cannon/MachineGun gunner seat and AP/HE ammo ordinal in each model's own
 *  sbw/vehicles JSON); SBW exposes vehicle targets through their occupants. */
object TankWeaponSelection {
    private const val GUNNER_SEAT = 0
    private const val CANNON_WEAPON = 0

    fun update(gunner: NpcEntity, target: LivingEntity) {
        val vehicle = gunner.vehicle ?: return
        if (Ports.vehicles.modelOf(vehicle) !is TankModel || Ports.vehicles.seatOf(vehicle, gunner) != GUNNER_SEAT) return
        VehicleCannonAmmo.select(vehicle, GUNNER_SEAT, CANNON_WEAPON, target)
    }
}

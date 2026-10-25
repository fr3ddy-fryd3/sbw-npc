package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack

/** Removes cargo before the first native reload, retaining the vehicle's charged battery. */
internal fun emptyVehicleCargo(vehicle: Entity) {
    require(vehicle is VehicleEntity)
    for (slot in 0 until vehicle.getContainerSize()) vehicle.setItem(slot, ItemStack.EMPTY)
}

/** Keeps an armed station temporarily unavailable to fire without consuming its ammunition. */
internal fun overheatVehicleGun(vehicle: Entity, passenger: Entity) {
    require(vehicle is VehicleEntity)
    requireNotNull(vehicle.getGunData(passenger)).overHeat.set(true)
}

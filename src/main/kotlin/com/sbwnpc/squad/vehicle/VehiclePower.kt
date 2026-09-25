package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.world.entity.Entity

/** Whether a vehicle has the power to actually go anywhere. An AI that only checks "is it alive,
 *  is it unlocked" walks a whole squad over to a flat APC, climbs in, and sits in it. */
object VehiclePower {
    /** Roughly 30 seconds of driving at full power. Enough to be worth boarding for. */
    const val DRIVE_RESERVE_TICKS = 600.0

    fun hasReserve(vehicle: Entity, ticks: Double = DRIVE_RESERVE_TICKS): Boolean =
        Ports.vehicles.hasPowerFor(vehicle, ticks)
}

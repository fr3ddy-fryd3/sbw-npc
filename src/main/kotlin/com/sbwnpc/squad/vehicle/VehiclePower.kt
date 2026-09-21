package com.sbwnpc.squad.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/**
 * Whether a vehicle has the power to actually go anywhere.
 *
 * SBW's engines simply stop responding when the battery runs down — `VehicleEngineUtils` zeroes
 * every input and bleeds the power off once `energy <= energyCost` — with no outward sign beyond
 * the vehicle not moving. An AI that only checks "is it alive, is it unlocked" therefore walks a
 * whole squad over to a flat APC, climbs in, and sits in it, which is exactly what was reported
 * in-game.
 *
 * The reserve is expressed in ticks of driving rather than as a flat number because the vehicles
 * differ by a factor of two in what they draw (`EnergyCostRate` 64 for a LAV, 128 for a T-90), and
 * because "enough for half a minute" is the question actually being asked.
 */
object VehiclePower {
    /** Roughly 30 seconds of driving at full power. Enough to be worth boarding for. */
    const val DRIVE_RESERVE_TICKS = 600.0

    /** `VehicleEntity.engineInfo` is deserialized lazily, on the vehicle's first engine tick, so a
     *  vehicle nobody has driven yet has none to read. Mid-range of what the ground vehicles
     *  actually draw (64 for a LAV, 128 for a T-90). */
    private const val ASSUMED_COST_RATE = 96.0

    fun hasReserve(vehicle: VehicleEntity, ticks: Double = DRIVE_RESERVE_TICKS): Boolean {
        // Not everything runs on a battery — the mortar's MaxEnergy is 0, and nothing with no
        // energy storage can ever be out of power.
        if (!vehicle.hasEnergyStorage()) return true
        val perTick = vehicle.engineInfo?.energyCostRate ?: ASSUMED_COST_RATE
        return vehicle.energy > perTick * ticks
    }
}

package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.atsuishio.superbwarfare.init.ModItems
import com.sbwnpc.squad.domain.port.VehicleModel
import com.sbwnpc.squad.domain.port.Vehicles
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB

object SbwVehicles : Vehicles {
    /** `VehicleEntity.engineInfo` is deserialized lazily, on the vehicle's first engine tick, so a
     *  vehicle nobody has driven yet has none to read. Mid-range of what the ground vehicles
     *  actually draw (64 for a LAV, 128 for a T-90). */
    private const val ASSUMED_COST_RATE = 96.0

    private val types: Map<VehicleModel, () -> EntityType<*>> = mapOf(
        TankModel.ZTZ_99A to { ModEntities.ZTZ_99A.get() },
        TankModel.T_90A to { ModEntities.T_90A.get() },
        TankModel.M1A2 to { ModEntities.M_1A_2.get() },
        HelicopterModel.MI_28 to { ModEntities.MI_28.get() },
        HelicopterModel.AH_6 to { ModEntities.AH_6.get() },
        TransportVehicle.LAV_25 to { ModEntities.LAV_25.get() },
        TransportVehicle.LAV_150 to { ModEntities.LAV_150.get() },
        TransportVehicle.BMP_2 to { ModEntities.BMP_2.get() },
    )

    override fun isVehicle(entity: Entity?): Boolean = entity is VehicleEntity

    override fun isOperational(entity: Entity?): Boolean =
        entity is VehicleEntity && entity.isAlive && !entity.isWreck

    override fun isWreck(vehicle: Entity): Boolean = vehicle is VehicleEntity && vehicle.isWreck

    override fun isLocked(vehicle: Entity): Boolean = vehicle is VehicleEntity && vehicle.locked

    override fun seatCount(vehicle: Entity): Int = (vehicle as? VehicleEntity)?.maxPassengers ?: 0

    override fun seatOf(vehicle: Entity, passenger: Entity): Int =
        (vehicle as? VehicleEntity)?.getSeatIndex(passenger) ?: -1

    override fun seating(vehicle: Entity): List<Entity?> =
        (vehicle as? VehicleEntity)?.getOrderedPassengers() ?: emptyList()

    override fun hasWeaponAt(vehicle: Entity, passenger: Entity): Boolean =
        vehicle is VehicleEntity && vehicle.getGunData(passenger) != null

    // SBW's engines simply stop responding when the battery runs down — `VehicleEngineUtils`
    // zeroes every input once `energy <= energyCost` — with no outward sign beyond the vehicle not
    // moving. Measured in ticks of driving because the vehicles differ by a factor of two in what
    // they draw (`EnergyCostRate` 64 for a LAV, 128 for a T-90).
    override fun hasPowerFor(vehicle: Entity, ticks: Double): Boolean {
        if (vehicle !is VehicleEntity) return false
        // Not everything runs on a battery — the mortar's MaxEnergy is 0, and nothing with no
        // energy storage can ever be out of power.
        if (!vehicle.hasEnergyStorage()) return true
        val perTick = vehicle.engineInfo?.energyCostRate ?: ASSUMED_COST_RATE
        return vehicle.energy > perTick * ticks
    }

    override fun modelOf(vehicle: Entity): VehicleModel? =
        if (vehicle !is VehicleEntity) null else types.entries.firstOrNull { it.value() == vehicle.type }?.key

    override fun create(level: ServerLevel, model: VehicleModel): Entity? =
        types[model]?.invoke()?.create(level) as? VehicleEntity

    override fun fuelAndArm(vehicle: Entity, model: VehicleModel) {
        if (vehicle !is VehicleEntity) return
        vehicle.energy = vehicle.maxEnergy
        when (model) {
            // Same main-gun AP/HE + coax rifle ammo + .50cal passenger ammo loadout for all three —
            // verified against each model's own sbw/vehicles/*.json: same four weapon/ammo slots.
            is TankModel -> {
                vehicle.setItem(0, ItemStack(ModItems.LARGE_SHELL_AP.get(), 64))
                vehicle.setItem(1, ItemStack(ModItems.LARGE_SHELL_HE.get(), 64))
                vehicle.setItem(2, ItemStack(ModItems.RIFLE_AMMO.get(), 64))
                vehicle.setItem(3, ItemStack(ModItems.HEAVY_AMMO.get(), 64))
            }
            // Cannon rounds with AP first and HE second — the order VehicleCannonAmmo assumes — plus
            // rockets. The AH-6's 20mm only takes HE, so it gets no AP stack.
            HelicopterModel.MI_28 -> {
                vehicle.setItem(0, ItemStack(ModItems.SMALL_SHELL_AP.get(), 64))
                vehicle.setItem(1, ItemStack(ModItems.SMALL_SHELL_HE.get(), 64))
                vehicle.setItem(2, ItemStack(ModItems.SMALL_ROCKET.get(), 16))
            }
            HelicopterModel.AH_6 -> {
                vehicle.setItem(0, ItemStack(ModItems.SMALL_SHELL_HE.get(), 64))
                vehicle.setItem(1, ItemStack(ModItems.SMALL_ROCKET.get(), 16))
            }
            // Small-caliber AP only, per user call — a short stack, not the full loadout the tanks get.
            is TransportVehicle -> vehicle.setItem(0, ItemStack(ModItems.SMALL_SHELL_AP.get(), 4))
            else -> {}
        }
    }

    // SBW turns a vehicle at zero health into a wreck on its next tick and detonates it on contact.
    override fun writeOff(vehicle: Entity) {
        (vehicle as? VehicleEntity)?.health = 0f
    }

    override fun within(level: Level, area: AABB, filter: (Entity) -> Boolean): List<Entity> =
        level.getEntitiesOfClass(VehicleEntity::class.java, area) { filter(it) }
}

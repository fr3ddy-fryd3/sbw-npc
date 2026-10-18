package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.registries.BuiltInRegistries

/** Nearby cargo supplements small arms without creating rounds or taking over movement. */
object VehicleAmmoSupply {
    fun tick(npc: NpcEntity) {
        if ((npc.tickCount + npc.id) % 40 != 0) return
        replenish(npc)
    }

    fun replenish(npc: NpcEntity): Int {
        SidearmKit.sync(npc)
        val weapons = listOf(npc.primaryGun(), npc.sidearmWeapon).distinct().filter {
            Ports.guns.isGun(it) && Ports.guns.reserve(it) < Ports.guns.magazineSize(it)
        }
        if (weapons.isEmpty()) return 0
        val nearby = npc.vehicle?.takeIf(Ports.vehicles::isOperational)?.let(::listOf)
            ?: Ports.vehicles.within(npc.level(), npc.boundingBox.inflate(6.0)) { Ports.vehicles.isOperational(it) && it.distanceToSqr(npc) <= 36.0 }
                .sortedBy { it.distanceToSqr(npc) }
        val faction = SquadTeams.factionOf(npc) ?: return 0
        var total = 0
        for (vehicle in nearby) {
            val owner = SquadTeams.factionOf(vehicle)
            if (owner != faction && !(owner == null && npc.vehicle === vehicle)) continue
            if (Ports.vehicles.isLocked(vehicle) && npc.vehicle !== vehicle) continue
            for (weapon in weapons) {
                val reserve = Ports.guns.reserve(weapon)
                val magazine = Ports.guns.magazineSize(weapon)
                if (reserve >= magazine) continue
                val full = if (weapon === npc.sidearmWeapon) SidearmKit.reserveRounds(weapon) else npc.npcClass.startingRounds(BuiltInRegistries.ITEM.getKey(weapon.item))
                val rounds = Ports.vehicles.takeSmallArmsAmmo(vehicle, weapon, minOf(full - reserve, magazine * 2).coerceAtLeast(0))
                if (rounds == 0) continue
                Ports.guns.topUpReserve(weapon, reserve + rounds)
                total += rounds
                DebugFlags.log(LogGroup.SUPPLY, "{} event=vehicle_ammo_taken vehicle={} weapon={} rounds={} reserve={}", npc.uuid, vehicle.uuid, BuiltInRegistries.ITEM.getKey(weapon.item), rounds, reserve + rounds)
            }
        }
        if (total > 0) SidearmKit.update(npc)
        return total
    }
}

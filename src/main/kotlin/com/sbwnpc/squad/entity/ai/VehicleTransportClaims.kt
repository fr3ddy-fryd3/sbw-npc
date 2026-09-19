package com.sbwnpc.squad.entity.ai

import java.util.UUID

/** Transient "who's claimed which seat in which vehicle" registry for [VehicleTransportBehaviour],
 *  same idiom as [MortarClaims]. A claim is a soft reservation made before the NPC has actually
 *  boarded (so squadmates converging on the same vehicle don't all target the same seat) and stays
 *  held while riding; released on arrival, death, or combat interrupting the transport attempt. */
object VehicleTransportClaims {
    private val drivers = HashMap<UUID, UUID>() // vehicle -> driver npc
    private val passengers = HashMap<UUID, MutableSet<UUID>>() // vehicle -> passenger npcs
    private val claimedVehicle = HashMap<UUID, UUID>() // npc -> vehicle
    private val combatGunners = HashMap<UUID, UUID>() // vehicle -> npc holding an armed seat

    fun driverOf(vehicle: UUID): UUID? = drivers[vehicle]

    fun vehicleOf(npc: UUID): UUID? = claimedVehicle[npc]

    fun combatGunnerOf(vehicle: UUID): UUID? = combatGunners[vehicle]

    fun claimedSeats(vehicle: UUID): Int = (if (drivers.containsKey(vehicle)) 1 else 0) + (passengers[vehicle]?.size ?: 0)

    fun claimDriver(vehicle: UUID, npc: UUID): Boolean {
        val existing = drivers[vehicle]
        if (existing != null && existing != npc) return false
        release(npc)
        drivers[vehicle] = npc
        claimedVehicle[npc] = vehicle
        return true
    }

    /** Union of claimed seats and whoever [aboard] (a vehicle's real passenger list) actually is —
     *  a real player never goes through the claim registry, so counting claims alone undercounts a
     *  vehicle a player already shares with our driver. [npc] itself, claimed or aboard or both,
     *  counts once. */
    fun occupiedOrClaimedSeats(vehicle: UUID, aboard: List<UUID>): Int {
        val occupants = HashSet<UUID>(aboard)
        drivers[vehicle]?.let { occupants += it }
        passengers[vehicle]?.let { occupants += it }
        return occupants.size
    }

    fun claimPassenger(vehicle: UUID, npc: UUID, maxSeats: Int, aboard: List<UUID> = emptyList()): Boolean {
        val set = passengers[vehicle]
        if (set?.contains(npc) == true) return true
        // Excluding npc from the occupancy count lets a crewman already aboard (its own seat, not
        // a new one) restore its claim in an otherwise-full vehicle.
        if (occupiedOrClaimedSeats(vehicle, aboard.filter { it != npc }) >= maxSeats) return false
        release(npc)
        passengers.getOrPut(vehicle) { mutableSetOf() }.add(npc)
        claimedVehicle[npc] = vehicle
        return true
    }

    fun claimCombatGunner(vehicle: UUID, npc: UUID): Boolean {
        val existing = combatGunners[vehicle]
        if (existing != null && existing != npc) return false
        combatGunners[vehicle] = npc
        return true
    }

    fun release(npc: UUID) {
        val vehicle = claimedVehicle.remove(npc)
        if (vehicle != null) {
            if (drivers[vehicle] == npc) drivers.remove(vehicle)
            passengers[vehicle]?.let { seats ->
                seats.remove(npc)
                if (seats.isEmpty()) passengers.remove(vehicle)
            }
        }
        combatGunners.entries.removeIf { it.value == npc }
    }

    fun clearAll() {
        drivers.clear()
        passengers.clear()
        claimedVehicle.clear()
        combatGunners.clear()
    }
}

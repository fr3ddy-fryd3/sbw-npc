package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.domain.port.Mobility
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Which vehicles an NPC may take (#255): one definition for the man who looks for a ride, the
 * squad that hands out seats, and the man who looks for a gun to man, so none of them offers a
 * vehicle another one refuses.
 */
object RideCandidates {
    /** Whether [vehicle] floats on water rather than drives on land. */
    fun isBoat(vehicle: Entity): Boolean = Ports.vehicles.mobility(vehicle) == Mobility.WATER

    /** A land vehicle with seats that runs, is charged, and no player keeps from [npc]. */
    fun isUsableGroundVehicle(vehicle: Entity, npc: NpcEntity): Boolean = isAvailable(vehicle, npc) && Ports.vehicles.mobility(vehicle) == Mobility.GROUND

    /** Afloat with seats, charged, and nobody's player at the helm. */
    fun isUsableBoat(vehicle: Entity, npc: NpcEntity): Boolean = isAvailable(vehicle, npc) && isBoat(vehicle) && vehicle.isInWater

    /**
     * An empty vehicle, not an aircraft, whose first seat is ready to fire: its guns aim by flying
     * it, so a man who cannot fly sat in it doing nothing. A vehicle claimed for a trip is not
     * taken over for its gun.
     */
    fun isSupportVehicle(vehicle: Entity): Boolean {
        if (Helicopters.isHelicopter(vehicle)) return false
        if (!Ports.vehicles.isOperational(vehicle) || Ports.vehicles.isLocked(vehicle) || vehicle.passengers.isNotEmpty()) return false
        if (VehicleTransportClaims.claimedSeats(vehicle.uuid) != 0) return false
        val seat = Ports.vehicles.seating(vehicle).indexOfFirst { it == null }
        return seat >= 0 && Ports.vehicles.seatReady(vehicle, seat)
    }

    /**
     * A player of ours may ride along in the back; a player at the wheel, or anyone not ours,
     * keeps the vehicle his: NPCs never take over a player's vehicle.
     */
    fun hasBlockingPlayerAboard(vehicle: Entity, npc: NpcEntity): Boolean {
        val players = vehicle.passengers.filterIsInstance<Player>()
        if (players.isEmpty()) return false
        if (players.any { Ports.vehicles.seatOf(vehicle, it) == 0 }) return true
        return players.any { !DriverAllegiance.isAlliedDriver(it, npc) }
    }

    private fun isAvailable(vehicle: Entity, npc: NpcEntity): Boolean =
        Ports.vehicles.isOperational(vehicle) && !Ports.vehicles.isLocked(vehicle) && Ports.vehicles.seatCount(vehicle) > 0 &&
            VehiclePower.hasReserve(vehicle) && !hasBlockingPlayerAboard(vehicle, npc)
}

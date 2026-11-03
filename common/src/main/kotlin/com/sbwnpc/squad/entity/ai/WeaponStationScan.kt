package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.tactics.StationHand
import com.sbwnpc.squad.ai.tactics.WeaponStation
import com.sbwnpc.squad.ai.tactics.WeaponStations
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.RideCandidates
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity

/**
 * What a team's choice of who mans a vehicle's gun ([WeaponStations]) is made from: its men free
 * to leave their place, and the empty armed vehicles near them whose gun bears on what they face.
 */
object WeaponStationScan {
    /** How far a man looks for an enemy vehicle's crew to shoot at from a gun. */
    const val THREAT_RANGE = 48.0

    /** A temporary weapon station cannot hold up an explicit march or the squad's withdrawal. */
    fun orderAllows(order: SquadOrder?): Boolean = order != SquadOrder.MOVE && order != SquadOrder.RETREAT

    /** Whether [npc] is free to go to a gun: on foot, no crew or mortar man, and nothing else holds him. */
    fun free(npc: NpcEntity): Boolean =
        npc.isAlive && npc.vehicle == null && npc.assignedVehicleId == null && npc.npcClass != NpcClass.MORTAR && !npc.isSuppressed() &&
            !npc.holdsAny(NpcActivity.SEAT, NpcActivity.VEHICLE, NpcActivity.DRONE, NpcActivity.ANTI_DRONE, NpcActivity.DUG_IN, NpcActivity.HEAL)

    /** [men] as hands for [WeaponStations.assign]. */
    fun hands(men: List<NpcEntity>): List<StationHand> = men.map { StationHand(it.uuid, Point3(it.x, it.y, it.z)) }

    /**
     * The empty armed vehicles ([RideCandidates.isSupportVehicle]) within [WeaponStations.REACH] of
     * one of [men], not held for another squad ([SeatHold]), whose free seat's gun can be aimed at
     * the nearest man's enemy. One box over the men.
     */
    fun stations(men: List<NpcEntity>, level: ServerLevel): List<WeaponStation> {
        val first = men.firstOrNull() ?: return emptyList()
        val box = men.drop(1).fold(first.boundingBox) { box, man -> box.minmax(man.boundingBox) }.inflate(WeaponStations.REACH)
        return Ports.vehicles.within(level, box).filter(RideCandidates::isSupportVehicle).mapNotNull { vehicle ->
            val man = men.minBy { it.distanceToSqr(vehicle) }
            val threat = threat(man, level) ?: return@mapNotNull null
            val seat = Ports.vehicles.seating(vehicle).indexOfFirst { it == null }
            if (SeatHold.refuses(vehicle, man, vehicle.passengers.size) || !Ports.vehicles.canAimAt(vehicle, man, threat, seat)) return@mapNotNull null
            WeaponStation(vehicle.uuid, Point3(vehicle.x, vehicle.y, vehicle.z))
        }
    }

    /** The enemy vehicle crew [npc] sees, else his own hostile target. */
    fun threat(npc: NpcEntity, level: ServerLevel): LivingEntity? =
        VehicleTargeting.closestVisibleHostileVehicleOccupant(npc, level, THREAT_RANGE) { VehicleTargeting.weaponPermits(npc, it) }
            ?: npc.target?.takeIf { it.isAlive && SquadTeams.isHostile(npc, it) }
}

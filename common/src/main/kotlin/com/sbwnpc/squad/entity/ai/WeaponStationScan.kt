package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.tactics.StationHand
import com.sbwnpc.squad.ai.tactics.WeaponStation
import com.sbwnpc.squad.ai.tactics.WeaponStations
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.RideCandidates
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/**
 * What a team's choice of who mans a vehicle's gun ([WeaponStations]) is made from: its men free
 * to leave their place, and the empty armed vehicles near them whose gun bears on what they face.
 */
object WeaponStationScan {
    /** How far a man looks for an enemy vehicle's crew to shoot at from a gun. */
    const val THREAT_RANGE = 48.0

    /**
     * Whether [squad]'s order lets a man take a temporary weapon station: never in a withdrawal,
     * and under MOVE only once the squad has come to its point. A march must not be held up by a
     * gun, but a squad standing on its MOVE point in a fight left an empty gun beside it unmanned
     * for 4.5 minutes (in-game 03.11). A man already at the gun ([manning]) keeps it while a third
     * of the squad is there, so he does not jump on and off at the edge of the point; a new MOVE
     * moves the point and takes him off.
     */
    fun orderAllows(squad: Squad?, level: ServerLevel, manning: Boolean = false): Boolean =
        when (squad?.order) {
            SquadOrder.RETREAT -> false
            SquadOrder.MOVE -> arrived(squad, level, if (manning) KEEP_SHARE else ARRIVED_SHARE)
            else -> true
        }

    private fun arrived(squad: Squad, level: ServerLevel, share: Double): Boolean {
        val point = squad.objective?.let(Vec3::atBottomCenterOf) ?: return false
        val men = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
        return men.isNotEmpty() && men.count { SquadFormation.reachedPoint(it, point, men.size) } >= men.size * share
    }

    /** Half the squad on its MOVE point: it has come there. */
    private const val ARRIVED_SHARE = 0.5

    /** A third still there keeps a man at his gun, see [orderAllows]. */
    private const val KEEP_SHARE = 1.0 / 3.0

    /** Whether [npc] is free to go to a gun: on foot, no crew or mortar man, and nothing else holds him. */
    fun free(npc: NpcEntity): Boolean =
        npc.isAlive && npc.vehicle == null && npc.assignedVehicleId == null && npc.npcClass != NpcClass.MORTAR && !npc.isSuppressed() &&
            !npc.holdsAny(NpcActivity.SEAT, NpcActivity.VEHICLE, NpcActivity.DRONE, NpcActivity.ANTI_DRONE, NpcActivity.DUG_IN, NpcActivity.HEAL)

    /** [men] as hands for [WeaponStations.assign]. */
    fun hands(men: List<NpcEntity>): List<StationHand> = men.map { StationHand(it.uuid, Point3(it.x, it.y, it.z)) }

    /**
     * The empty armed vehicles ([RideCandidates.isSupportVehicle]) within [WeaponStations.REACH] of
     * one of [men] whose free seat's gun can be aimed at the nearest man's enemy. One box over the men.
     */
    fun stations(men: List<NpcEntity>, level: ServerLevel): List<WeaponStation> {
        val first = men.firstOrNull() ?: return emptyList()
        val box = men.drop(1).fold(first.boundingBox) { box, man -> box.minmax(man.boundingBox) }.inflate(WeaponStations.REACH)
        return Ports.vehicles.within(level, box).filter(RideCandidates::isSupportVehicle).mapNotNull { vehicle ->
            if (!bears(men.minBy { it.distanceToSqr(vehicle) }, vehicle.uuid, level)) return@mapNotNull null
            WeaponStation(vehicle.uuid, Point3(vehicle.x, vehicle.y, vehicle.z))
        }
    }

    /** Whether the gun of [vehicle]'s free seat can be laid on [npc]'s enemy. */
    fun bears(npc: NpcEntity, vehicle: java.util.UUID, level: ServerLevel): Boolean {
        val gun = level.getEntity(vehicle)?.takeIf(Ports.vehicles::isVehicle) ?: return false
        val threat = threat(npc, level) ?: return false
        return Ports.vehicles.canAimAt(gun, npc, threat, Ports.vehicles.seating(gun).indexOfFirst { it == null })
    }

    /** The enemy vehicle crew [npc] sees, else his own hostile target. */
    fun threat(npc: NpcEntity, level: ServerLevel): LivingEntity? =
        VehicleTargeting.closestVisibleHostileVehicleOccupant(npc, level, THREAT_RANGE) { VehicleTargeting.weaponPermits(npc, it) }
            ?: npc.target?.takeIf { it.isAlive && SquadTeams.isHostile(npc, it) }
}

package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.tactics.JourneyContact
import com.sbwnpc.squad.ai.tactics.SeatAllocation
import com.sbwnpc.squad.ai.tactics.SeatVehicle
import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.HostileMobs
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.vehicle.RideCandidates
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The measurements behind a squad's journey ([com.sbwnpc.squad.ai.tactics.SquadJourney]): how far
 * it is from its objective, members already aboard included, and what each man has close by.
 */
object SquadTransit {
    /** Beyond this distance a squad may take nearby transport instead of walking. */
    const val TRANSPORT_DISTANCE = 100.0

    /**
     * Whether [npc] marches on with his travelling squad ([com.sbwnpc.squad.ai.integration.NativeTactics.travelling])
     * whatever he has in his sights: he fires from where he is, and his feet stay with the journey.
     * Out of ammunition he leaves it for his own rule ([NpcEntity.lowAmmoFallback]).
     */
    fun marching(npc: NpcEntity): Boolean = npc.lowAmmoFallback() == null && NativeTactics.travelling(npc)

    /**
     * What [npc]'s own target makes of his squad's journey: an enemy NPC or player within his
     * engagement distance, or a mob within [HostileMobs.CLOSE_RANGE]. A target farther off is
     * no contact for the journey: the squad travels on and he fires from where he is.
     */
    fun contact(npc: NpcEntity): JourneyContact {
        val target = npc.target?.takeIf { it.isAlive } ?: return JourneyContact.NONE
        val distance = npc.distanceTo(target).toDouble()
        // A mob on no side counts while it goes for the squad; anything else he fights (another
        // squad, a player, another mod's soldier) is an enemy.
        return if (target is Mob && target !is NpcEntity && HostileMobs.threatens(target, npc)) {
            JourneyContact(enemy = false, mob = distance <= HostileMobs.CLOSE_RANGE)
        } else {
            val engage = maxOf(npc.npcClass.assaultDistance, CombatPositionSafety.clearance(npc.npcClass) + CombatPositionSafety.APPROACH_MARGIN)
            JourneyContact(enemy = distance <= engage, mob = false)
        }
    }

    /** Whether the loaded living squad is still far from its ordered objective in this dimension. */
    fun distant(squad: Squad, level: ServerLevel): Boolean = (objectiveDistance(squad, level) ?: 0.0) > TRANSPORT_DISTANCE

    /** How far the loaded living squad stands from its ordered objective ([medianDistance]), or null with no objective in this dimension or nobody loaded. */
    fun objectiveDistance(squad: Squad, level: ServerLevel): Double? {
        if (squad.objectiveDimension?.let { it != level.dimension() } == true) return null
        val objective = squad.objective ?: return null
        val positions = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }
            .filter { it.isAlive && it.squadId == squad.id }
            .map { it.position() }
        return medianDistance(positions, Vec3.atBottomCenterOf(objective))
    }

    internal fun distant(positions: List<Vec3>, objective: Vec3): Boolean = (medianDistance(positions, objective) ?: 0.0) > TRANSPORT_DISTANCE

    /** A coordinate-wise median keeps a lone straggler from changing the journey for everyone. */
    private fun medianDistance(positions: List<Vec3>, objective: Vec3): Double? {
        if (positions.isEmpty()) return null
        val middle = positions.size / 2
        val center = Vec3(positions.map { it.x }.sorted()[middle], positions.map { it.y }.sorted()[middle], positions.map { it.z }.sorted()[middle])
        return center.distanceTo(objective)
    }

    /**
     * The ground vehicles [riders] of [squad] could be given seats in ([SeatAllocation]): within
     * [SeatAllocation.REACH] of one of them, usable ([RideCandidates.isUsableGroundVehicle]), not a
     * crew's own vehicle, and either the squad's already or nobody else's, with no other NPC
     * aboard or bound for it. One box over the riders for the whole squad.
     */
    fun seatVehicles(squad: Squad, riders: List<NpcEntity>, level: ServerLevel): List<SeatVehicle> {
        val first = riders.firstOrNull() ?: return emptyList()
        val box = riders.drop(1).fold(first.boundingBox) { box, man -> box.minmax(man.boundingBox) }.inflate(SeatAllocation.REACH)
        val crews = squad.members.mapNotNullTo(HashSet()) { (level.getEntity(it) as? NpcEntity)?.assignedVehicleId }
        val bound = squad.members.mapNotNull(VehicleTransportClaims::vehicleOf).groupingBy { it }.eachCount()
        return Ports.vehicles.within(level, box).mapNotNull { vehicle ->
            if (vehicle.uuid in crews || !RideCandidates.isUsableGroundVehicle(vehicle, first)) return@mapNotNull null
            val npcs = vehicle.passengers.filterIsInstance<NpcEntity>()
            val ours = npcs.isNotEmpty() || bound.containsKey(vehicle.uuid)
            val strangers = npcs.any { it.squadId != squad.id } || VehicleTransportClaims.claimedSeats(vehicle.uuid) > (bound[vehicle.uuid] ?: 0)
            if (strangers) return@mapNotNull null
            SeatVehicle(
                vehicle.uuid,
                Point3(vehicle.x, vehicle.y, vehicle.z),
                free = Ports.vehicles.seatCount(vehicle) - vehicle.passengers.size,
                driven = Ports.vehicles.seating(vehicle).firstOrNull() != null,
                ours = ours,
            )
        }
    }
}

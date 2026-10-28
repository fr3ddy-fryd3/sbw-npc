package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** One distance decision for the squad's journey, including members already aboard. */
object SquadTransit {
    /** Beyond this distance a squad may take nearby transport instead of walking. */
    const val TRANSPORT_DISTANCE = 100.0

    /** A remembered enemy beyond the normal engagement distance must not stop the journey. */
    fun marching(npc: NpcEntity): Boolean {
        // Cover and treatment still own movement through the executors' existing role guards.
        // Incoming fire forbids boarding, but does not turn a remote contact into a local post.
        if (npc.lowAmmoFallback() != null) return false
        val target = npc.target
        val engage = maxOf(npc.npcClass.assaultDistance, CombatPositionSafety.clearance(npc.npcClass) + CombatPositionSafety.APPROACH_MARGIN)
        if (target != null && npc.distanceToSqr(target) <= engage * engage) return false
        val squad = npc.currentSquad() ?: return false
        return distant(squad, npc.level() as ServerLevel)
    }

    /**
     * Whether the squad as a whole is fighting instead of travelling: any man of it has a target and is not
     * marching ([marching]). One answer for every man, so that a mob or an enemy on one of them cannot leave
     * him fighting while the rest board and the vehicle drives off without him (#198).
     */
    fun engaged(npc: NpcEntity): Boolean {
        if (npc.target != null && !marching(npc)) return true
        val squad = npc.currentSquad() ?: return false
        val level = npc.level() as ServerLevel
        return squad.members.any { id ->
            val member = level.getEntity(id) as? NpcEntity
            member != null && member !== npc && member.isAlive && member.squadId == squad.id && member.target != null && !marching(member)
        }
    }

    /** Whether the loaded living squad is still far from its ordered objective in this dimension. */
    fun distant(squad: Squad, level: ServerLevel): Boolean {
        if (squad.objectiveDimension?.let { it != level.dimension() } == true) return false
        val objective = squad.objective ?: return false
        val positions = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }
            .filter { it.isAlive && it.squadId == squad.id }
            .map { it.position() }
        return distant(positions, Vec3.atBottomCenterOf(objective))
    }

    /** A coordinate-wise median keeps a lone straggler from changing the journey for everyone. */
    internal fun distant(positions: List<Vec3>, objective: Vec3): Boolean {
        if (positions.isEmpty()) return false
        val middle = positions.size / 2
        val center = Vec3(positions.map { it.x }.sorted()[middle], positions.map { it.y }.sorted()[middle], positions.map { it.z }.sorted()[middle])
        return center.distanceTo(objective) > TRANSPORT_DISTANCE
    }
}

package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3

/** One distance decision for the squad's journey, including members already aboard. */
object SquadTransit {
    /** Beyond this distance a squad may take nearby transport instead of walking. */
    const val TRANSPORT_DISTANCE = 100.0

    /**
     * Whether [npc] is on the journey: the squad is [travelling]. A remembered enemy beyond the
     * engagement distance must not stop it, so the answer is one for the whole squad (#198).
     */
    fun marching(npc: NpcEntity): Boolean {
        // Cover and treatment still own movement through the executors' existing role guards.
        // Incoming fire forbids boarding, but does not turn a remote contact into a local post.
        if (npc.lowAmmoFallback() != null) return false
        val squad = npc.currentSquad() ?: return false
        return travelling(squad, npc.level() as ServerLevel)
    }

    /**
     * The squad's one decision: travel on towards a far objective (by vehicle where there is one), or
     * engage. It travels while it is [distant] and nobody of it is in [closeCombat], so a mob or an enemy
     * on one man cannot leave him fighting while the rest board and the vehicle drives off without him.
     */
    fun travelling(squad: Squad, level: ServerLevel): Boolean = distant(squad, level) && !closeCombat(squad, level)

    /** [closeCombat] for the squad of [npc], or for [npc] alone when he has none. */
    fun engaged(npc: NpcEntity): Boolean {
        val squad = npc.currentSquad() ?: return closeContact(npc, null)
        return closeCombat(squad, npc.level() as ServerLevel)
    }

    /**
     * Whether any man of the squad is in close combat: an enemy NPC or player within his engagement
     * distance, or a hostile mob that is after a man of the squad. Fire from further off is a reason to
     * leave, not to stay, so it never counts.
     */
    fun closeCombat(squad: Squad, level: ServerLevel): Boolean =
        squad.members.any { id ->
            val member = level.getEntity(id) as? NpcEntity
            member != null && member.isAlive && member.squadId == squad.id && closeContact(member, squad)
        }

    private fun closeContact(member: NpcEntity, squad: Squad?): Boolean {
        val target = member.target ?: return false
        if (target is Mob && target !is NpcEntity) return target.target?.let { it.uuid in (squad?.members ?: listOf(member.uuid)) } == true
        val engage = maxOf(member.npcClass.assaultDistance, CombatPositionSafety.clearance(member.npcClass) + CombatPositionSafety.APPROACH_MARGIN)
        return member.distanceToSqr(target) <= engage * engage
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

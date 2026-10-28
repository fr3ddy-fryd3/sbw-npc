package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.HostileMobs
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Mob
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
     * Whether the squad is in close combat, so that nobody of it boards (#198). One answer for the whole
     * squad, so a fight on one man cannot leave him out while the others drive off.
     *
     * It is close combat when an enemy NPC or player is within a man's engagement distance, or a mob
     * that threatens him ([HostileMobs.threatens]) is within [HostileMobs.CLOSE_RANGE] of him. Fire from
     * further off never counts: everyone boards. Once a man of the squad is on the vehicle trip
     * ([NpcEntity.vehicleTransport]) the squad has decided to travel, and a mob closing in no longer cancels the trip; whoever
     * sees it shoots from where he stands.
     */
    fun engaged(npc: NpcEntity): Boolean {
        val squad = npc.currentSquad() ?: return enemyClose(npc)
        val level = npc.level() as ServerLevel
        val now = level.gameTime
        if (squad.engagedAt == now) return squad.engagedAnswer
        val men = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive && it.squadId == squad.id }
        val travelling = men.any { it.vehicleTransport }
        val answer = men.any(::enemyClose) || (!travelling && men.any { mobClose(it, level) })
        squad.engagedAt = now
        squad.engagedAnswer = answer
        return answer
    }

    /**
     * Whether the squad has decided to travel: it is [distant] from its objective and not [engaged].
     * The vehicle is the cover then, so nobody runs for cover ([SeekCoverBehaviour]) and a hit man runs
     * on to the ride.
     */
    fun travelling(npc: NpcEntity): Boolean {
        val squad = npc.currentSquad() ?: return false
        return distant(squad, npc.level() as ServerLevel) && !engaged(npc)
    }

    private fun enemyClose(man: NpcEntity): Boolean {
        val target = man.target ?: return false
        if (target is Mob && target !is NpcEntity) return false
        val engage = maxOf(man.npcClass.assaultDistance, CombatPositionSafety.clearance(man.npcClass) + CombatPositionSafety.APPROACH_MARGIN)
        return man.distanceToSqr(target) <= engage * engage
    }

    private fun mobClose(man: NpcEntity, level: ServerLevel): Boolean {
        val reach = HostileMobs.CLOSE_RANGE
        return level.getEntitiesOfClass(Mob::class.java, man.boundingBox.inflate(reach)) {
            it !is NpcEntity && it.isAlive && it.distanceToSqr(man) <= reach * reach && HostileMobs.threatens(it, man)
        }.isNotEmpty()
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

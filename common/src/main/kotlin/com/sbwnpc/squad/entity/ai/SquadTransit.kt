package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.tactics.JourneyContact
import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.HostileMobs
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
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
        return if (target is NpcEntity || target is Player) {
            val engage = maxOf(npc.npcClass.assaultDistance, CombatPositionSafety.clearance(npc.npcClass) + CombatPositionSafety.APPROACH_MARGIN)
            JourneyContact(enemy = distance <= engage, mob = false)
        } else {
            JourneyContact(enemy = false, mob = distance <= HostileMobs.CLOSE_RANGE)
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

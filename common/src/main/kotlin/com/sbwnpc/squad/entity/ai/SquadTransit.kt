package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** One distance decision for the squad's journey, including members already aboard. */
object SquadTransit {
    /** Beyond this distance a squad may take nearby transport instead of walking. */
    const val TRANSPORT_DISTANCE = 100.0

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

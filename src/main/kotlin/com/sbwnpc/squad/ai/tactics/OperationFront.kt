package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class FormationActor(val id: UUID, val squad: UUID, val position: Point3, val engagementDistance: Double = 56.0)
data class FrontProposal(val duty: CombatDuty, val anchor: Point3, val watch: Point3?)

/** Proposes a curved front from reported enemy extent; surplus capacity covers depth and rear. */
object OperationFront {
    fun propose(actors: List<FormationActor>, enemies: List<Point3>, focus: Point3?, priorDuties: Map<UUID, CombatDuty> = emptyMap()): Map<UUID, FrontProposal> {
        if (actors.isEmpty()) return emptyMap()
        require(actors.map { it.id }.distinct().size == actors.size)
        val center = Point3(actors.map { it.position.x }.average(), actors.map { it.position.y }.average(), actors.map { it.position.z }.average())
        val dx = (focus?.x ?: center.x) - center.x
        val dz = (focus?.z ?: center.z + 1) - center.z
        val length = hypot(dx, dz)
        val fx = if (length > 0.01) dx / length else 0.0
        val fz = if (length > 0.01) dz / length else 1.0
        val sx = -fz
        val sz = fx
        val origin = focus ?: center
        fun projection(point: Point3) = (point.x - origin.x) * sx + (point.z - origin.z) * sz
        val extents = enemies.map(::projection)
        val width = max(48.0, (extents.maxOrNull() ?: 0.0) - (extents.minOrNull() ?: 0.0) + 24)
        val columns = min(actors.size, max(1, ceil(width / 3).toInt()))
        val minimumSupport = min(actors.size, max(1, actors.size / 3))
        val frontCount = min(actors.size - minimumSupport, columns * 2)
        val supportCount = min(actors.size - frontCount, max(minimumSupport, frontCount / 2))
        // Round-robin the squads before allocating duties, then preserve lateral order per duty.
        val squads = actors.groupBy { it.squad }.toSortedMap(compareBy { it.toString() }).values.map { group -> group.sortedWith(compareBy<FormationActor> { it.engagementDistance }.thenBy { projection(it.position) }.thenBy { it.id.toString() }) }
        val balanced = (0 until squads.maxOf { it.size }).flatMap { index -> squads.mapNotNull { it.getOrNull(index) } }
        val remaining = balanced.toMutableList()
        fun take(duty: CombatDuty, count: Int): List<FormationActor> {
            val selected = remaining.sortedBy { actor ->
                when (priorDuties[actor.id]) {
                    duty -> 0
                    null -> 1
                    else -> 2
                }
            }.take(count)
            val ids = selected.mapTo(HashSet()) { it.id }
            remaining.removeIf { it.id in ids }
            return selected
        }
        val groups = listOf(
            take(CombatDuty.MOVE, frontCount) to CombatDuty.MOVE,
            take(CombatDuty.FIRE, supportCount) to CombatDuty.FIRE,
            remaining.toList() to CombatDuty.REAR_GUARD,
        )
        val result = LinkedHashMap<UUID, FrontProposal>()
        for ((members, duty) in groups) {
            members.sortedWith(compareBy<FormationActor> { projection(it.position) }.thenBy { it.id.toString() }).forEachIndexed { index, actor ->
                val lateral: Double
                val depth: Double
                when (duty) {
                    CombatDuty.MOVE -> {
                        val rowColumns = min(columns, frontCount)
                        lateral = (index % rowColumns - (rowColumns - 1) / 2.0) * 3 + (index / rowColumns % 2) * 0.75
                        depth = -actor.engagementDistance - index / rowColumns * 8 - abs(lateral) * 0.15
                    }

                    CombatDuty.FIRE -> {
                        lateral = (index - (members.size - 1) / 2.0) * 4 + 1.5
                        depth = -actor.engagementDistance - if (actor.engagementDistance >= 48) 20 else 0
                    }

                    else -> {
                        lateral = (index % 12 - 5.5) * 3
                        depth = -84.0 - index / 12 * 4
                    }
                }
                val anchor = Point3(origin.x + fx * depth + sx * lateral, origin.y, origin.z + fz * depth + sz * lateral)
                val watch = if (duty == CombatDuty.REAR_GUARD) Point3(anchor.x - fx * 20, anchor.y, anchor.z - fz * 20) else focus
                result[actor.id] = FrontProposal(duty, anchor, watch)
            }
        }
        return result
    }
}

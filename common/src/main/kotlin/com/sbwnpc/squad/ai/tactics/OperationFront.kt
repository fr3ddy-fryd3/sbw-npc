package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class FormationActor(val id: UUID, val squad: UUID, val position: Point3, val engagementDistance: Double = 56.0)
data class FrontProposal(val duty: CombatDuty, val anchor: Point3, val watch: Point3?)

/** Proposes a curved front from reported enemy extent; surplus capacity covers depth and rear. */
object OperationFront {
    /**
     * Each of [actors] mapped to its duty and post on a front facing [focus]. Without [approach]
     * the front faces it from the actors' centre. With [approach] (a bearing in radians from
     * [focus], see [ApproachSectors]) the front stands on that bearing instead, and is moved back
     * so every one of [enemies], not only [focus], is at least each row's distance in front of it.
     */
    fun propose(actors: List<FormationActor>, enemies: List<Point3>, focus: Point3?, priorDuties: Map<UUID, CombatDuty> = emptyMap(), approach: Double? = null): Map<UUID, FrontProposal> {
        if (actors.isEmpty()) return emptyMap()
        require(actors.map { it.id }.distinct().size == actors.size)
        val center = Point3(actors.map { it.position.x }.average(), actors.map { it.position.y }.average(), actors.map { it.position.z }.average())
        val dx = approach?.let { -cos(it) } ?: ((focus?.x ?: center.x) - center.x)
        val dz = approach?.let { -sin(it) } ?: ((focus?.z ?: center.z + 1) - center.z)
        val length = hypot(dx, dz)
        val fx = if (length > 0.01) dx / length else 0.0
        val fz = if (length > 0.01) dz / length else 1.0
        val sx = -fz
        val sz = fx
        val origin = focus ?: center
        fun projection(point: Point3) = (point.x - origin.x) * sx + (point.z - origin.z) * sz
        val extents = enemies.map(::projection)
        val enemyMin = extents.minOrNull() ?: 0.0
        val enemyMax = extents.maxOrNull() ?: 0.0
        // Depth is measured from the enemy nearest the line, so the line is out of reach of all of them.
        val reach = if (approach == null) 0.0 else min(0.0, enemies.minOfOrNull { (it.x - origin.x) * fx + (it.z - origin.z) * fz } ?: 0.0)
        val width = max(48.0, enemyMax - enemyMin + 24)
        // Capacity per row follows the enemy width at the former 3-block pitch; the wider spacing
        // lengthens the row rather than sending more men to the rear.
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

        // Each shooter fires at the part of the enemy line straight ahead of it, or at its nearer
        // end. [through] is a lateral at [rowDepth]; the result is the lateral at [depth] (further
        // back) whose shot crosses that row exactly there, so it passes a gap, not a body.
        fun lateralThrough(through: Double, rowDepth: Double, depth: Double): Double {
            if (depth >= rowDepth || through in enemyMin..enemyMax) return through
            val crossing = (rowDepth - depth) / -depth
            return (through - through.coerceIn(enemyMin, enemyMax) * crossing) / (1 - crossing)
        }
        val (movers, supporters) = groups[0].first to groups[1].first
        val rowColumns = max(1, min(columns, frontCount))
        val frontDepth = -(movers.map { it.engagementDistance }.sorted().getOrNull(movers.size / 2) ?: 0.0)
        val frontHalf = if (movers.isEmpty()) -SPACING else (rowColumns - 1) / 2.0 * SPACING
        val wings = supporters.sortedWith(compareBy<FormationActor> { projection(it.position) }.thenBy { it.id.toString() }).let { sorted ->
            val left = sorted.take(sorted.size / 2).reversed()
            val right = sorted.drop(sorted.size / 2)
            left.withIndex().associate { it.value.id to (-1 to it.index) } + right.withIndex().associate { it.value.id to (1 to it.index) }
        }
        val result = LinkedHashMap<UUID, FrontProposal>()
        for ((members, duty) in groups) {
            members.sortedWith(compareBy<FormationActor> { projection(it.position) }.thenBy { it.id.toString() }).forEachIndexed { index, actor ->
                val lateral: Double
                val depth: Double
                when (duty) {
                    // Two rows: the second stands on the shot lines through the first row's gaps.
                    CombatDuty.MOVE -> {
                        val column = (index % rowColumns - (rowColumns - 1) / 2.0) * SPACING
                        val rowDepth = -actor.engagementDistance - abs(column) * 0.15
                        if (index / rowColumns % 2 == 0) {
                            lateral = column
                            depth = rowDepth
                        } else {
                            depth = rowDepth - ROW_DEPTH
                            lateral = lateralThrough(column + SPACING / 2, rowDepth, depth)
                        }
                    }

                    // Support fires from wings beyond the ends of the front, never through it.
                    CombatDuty.FIRE -> {
                        val (side, rank) = wings.getValue(actor.id)
                        val outside = side * (frontHalf + SPACING * 1.5 + rank * SPACING)
                        depth = -actor.engagementDistance - abs(outside) * 0.15
                        lateral = if (movers.isEmpty()) outside else lateralThrough(outside, frontDepth - abs(outside) * 0.15, depth)
                    }

                    else -> {
                        lateral = (index % 12 - 5.5) * 3
                        depth = -84.0 - index / 12 * 4
                    }
                }
                val anchor = Point3(origin.x + fx * (depth + reach) + sx * lateral, origin.y, origin.z + fz * (depth + reach) + sz * lateral)
                val watch = if (duty == CombatDuty.REAR_GUARD) Point3(anchor.x - fx * 20, anchor.y, anchor.z - fz * 20) else focus
                result[actor.id] = FrontProposal(duty, anchor, watch)
            }
        }
        return result
    }

    /** Lateral distance between neighbours in one row; a gap is half of it. */
    private const val SPACING = 4.0

    /** How far behind the first row the second one stands. */
    private const val ROW_DEPTH = 6.0
}

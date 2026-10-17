package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * The bearing from the objective each group of an attack builds its line on, kept for the whole
 * attack so no group is moved to another side midway.
 *
 * One straight front for every squad of an operation put the men in front of and behind each
 * other: in-game 17.10, 48 attackers in three squads were held by friendly-fire gates 60% of the
 * time and churned for posts (#58). The pinning group, nearest the objective at the first contact,
 * attacks head-on; the others take sectors [FLANK_ANGLE] to either side, never more than 90°, so
 * no group fires across the point at another.
 *
 * @param K what a group is keyed by: a squad, or one half of a squad attacking alone.
 */
class ApproachSectors<K : Any> {
    private var headOn: Double? = null
    private val offsets = LinkedHashMap<K, Double>()

    /**
     * Each of [groups] (keyed to its centre) mapped to its bearing in radians, measured from the
     * [objective] towards the group's line. Empty until [contact]: before the defence is met
     * there is no head-on line to take sectors from. [pinning] names the group that attacks
     * head-on; without it the group nearest the objective does.
     */
    fun bearings(objective: Point3, groups: Map<K, Point3>, contact: Boolean, pinning: K? = null): Map<K, Double> {
        if (groups.isEmpty()) return emptyMap()
        offsets.keys.retainAll(groups.keys)
        if (headOn == null) {
            if (!contact) return emptyMap()
            val first = pinning ?: groups.minBy { distance(it.value, objective) }.key
            headOn = bearing(objective, groups.getValue(first))
            offsets[first] = 0.0
        }
        val head = headOn!!
        // The pinning group fell: the next one to join takes the head-on line.
        if (offsets.values.none { it == 0.0 }) {
            (pinning?.takeIf { it in groups && it !in offsets } ?: groups.keys.filterNot { it in offsets }.minByOrNull { distance(groups.getValue(it), objective) })
                ?.let { offsets[it] = 0.0 }
        }
        groups.keys.filterNot { it in offsets }.sortedBy { distance(groups.getValue(it), objective) }.forEach { key ->
            val stands = normalize(bearing(objective, groups.getValue(key)) - head)
            offsets[key] = SECTORS.minWith(compareBy<Double> { sector -> offsets.values.count { it == sector } }.thenBy { abs(normalize(it - stands)) })
        }
        return offsets.mapValues { normalize(head + it.value) }
    }

    companion object {
        /** How far to either side of the head-on line a flanking group's sector lies (60°). */
        const val FLANK_ANGLE = PI / 3

        private val SECTORS = listOf(0.0, FLANK_ANGLE, -FLANK_ANGLE)

        /** How far round the objective a flanking man walks before taking the next arc waypoint (30°). */
        const val ARC_STEP = PI / 6

        // Within this much of a waypoint's bearing it counts as reached; without it a man standing
        // just past a step boundary was sent to a waypoint where he already stood.
        private const val ARC_TOLERANCE = PI / 18

        /**
         * The next point on the arc round the [objective] for a man at [from] on his way to his
         * sector's [bearing], or null once he is near enough the sector to make for his post.
         *
         * The arc keeps him at least [radius] (his line's distance) from the objective, out of the
         * defenders' reach: walking straight to a sector on the far flank crosses the point. The
         * waypoints are fixed steps back from the sector, so they do not move as he walks.
         */
        fun arcWaypoint(objective: Point3, from: Point3, bearing: Double, radius: Double): Point3? {
            val delta = normalize(bearing - bearing(objective, from))
            val steps = ceil((abs(delta) - ARC_TOLERANCE) / ARC_STEP).toInt() - 1
            if (steps <= 0) return null
            val at = bearing - sign(delta) * steps * ARC_STEP
            val range = max(radius, distance(objective, from))
            return Point3(objective.x + cos(at) * range, from.y, objective.z + sin(at) * range)
        }

        /** A squad attacking alone splits only when its manoeuvre group has at least this many. */
        const val MIN_MANOEUVRE = 2

        /**
         * The members of a squad attacking alone that take a flank: half its [riflemen] and all its
         * [assault] men (grenadiers, shotgunners), keeping those in [previous] where it can. The
         * machine gunners, snipers, the other riflemen and everyone else hold the head-on line and
         * fire. Empty when too few would go for a flank to be worth it.
         */
        fun manoeuvreGroup(riflemen: Collection<UUID>, assault: Collection<UUID>, previous: Set<UUID>): Set<UUID> {
            val sorted = riflemen.sortedWith(compareBy<UUID> { it !in previous }.thenBy { it.toString() })
            val group = sorted.take((sorted.size + 1) / 2) + assault
            return if (group.size >= MIN_MANOEUVRE) group.toSet() else emptySet()
        }

        private fun bearing(from: Point3, to: Point3) = atan2(to.z - from.z, to.x - from.x)

        private fun distance(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)

        private fun normalize(angle: Double): Double {
            var result = angle % (2 * PI)
            if (result > PI) result -= 2 * PI
            if (result < -PI) result += 2 * PI
            return result
        }
    }
}

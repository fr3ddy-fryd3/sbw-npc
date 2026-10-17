package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

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

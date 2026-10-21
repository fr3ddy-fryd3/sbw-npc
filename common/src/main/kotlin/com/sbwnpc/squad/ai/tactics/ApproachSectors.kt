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
        val joining = groups.keys.filterNot { it in offsets }
        val stands = joining.associateWith { normalize(bearing(objective, groups.getValue(it)) - head) }
        offsets.putAll(assign(joining, stands))
        return offsets.mapValues { normalize(head + it.value) }
    }

    /**
     * The [joining] groups' sectors all at once: as even across the sectors as the groups already
     * there allow, then with the least walking round the objective from where each [stands].
     *
     * Chosen one by one, the second squad of three, standing nearly head-on, took the flank on the
     * side where the third already stood, and the third walked 104° round the point across the
     * pinning squad's front (headless battle on the owner's terrain, 17.10).
     *
     * Trying every combination took 3^n steps: 28 squads ordered to attack together froze the
     * server (#140). The fullest sector's count comes first, so it is worked out directly; the
     * walking is then least over how many groups each sector still takes. Ties go to the earlier
     * sector in [SECTORS] for the earlier group, as the full search chose.
     */
    private fun assign(joining: List<K>, stands: Map<K, Double>): Map<K, Double> {
        if (joining.isEmpty()) return emptyMap()
        val present = SECTORS.map { sector -> offsets.values.count { it == sector } }
        var fullest = present.max()
        while (present.sumOf { fullest - it } < joining.size) fullest++
        val walk = joining.map { key -> SECTORS.map { abs(normalize(it - stands.getValue(key))) } }
        val chosen = SectorFill(present.map { fullest - it }, walk).choose()
        return joining.indices.associate { joining[it] to SECTORS[chosen[it]] }
    }

    companion object {
        /** How far to either side of the head-on line a flanking group's sector lies (60°). */
        const val FLANK_ANGLE = PI / 3

        private val SECTORS = listOf(0.0, FLANK_ANGLE, -FLANK_ANGLE)

        /** How far round the objective a flanking man walks before taking the next arc waypoint (30°). */
        const val ARC_STEP = PI / 6

        /**
         * How far behind the lines (12 blocks) the arc runs: walked on the line's own radius, a
         * flank crossed the pinning squad's firing line.
         */
        const val ARC_MARGIN = 12.0

        // Within this much of a waypoint's bearing it counts as reached; without it a man standing
        // just past a step boundary was sent to a waypoint where he already stood.
        private const val ARC_TOLERANCE = PI / 18

        /**
         * The next point on the arc round the [objective] for a man at [from] on his way to his
         * sector's [bearing], or null once he is near enough the sector to make for his post.
         *
         * The arc keeps him [ARC_MARGIN] beyond [radius] (his line's distance) from the objective,
         * out of the defenders' reach and behind the other groups' lines: walking straight to a sector on the far flank crosses the point. The
         * waypoints are fixed steps back from the sector, so they do not move as he walks.
         */
        fun arcWaypoint(objective: Point3, from: Point3, bearing: Double, radius: Double): Point3? {
            val delta = normalize(bearing - bearing(objective, from))
            val steps = ceil((abs(delta) - ARC_TOLERANCE) / ARC_STEP).toInt() - 1
            if (steps <= 0) return null
            val at = bearing - sign(delta) * steps * ARC_STEP
            val range = max(radius + ARC_MARGIN, distance(objective, from))
            return Point3(objective.x + cos(at) * range, from.y, objective.z + sin(at) * range)
        }

        /** A squad attacking alone splits only when each of its two groups has at least this many. */
        const val MIN_GROUP = 3

        /**
         * The members of a squad of [size] men attacking alone that take a flank: half its [riflemen] and all its
         * [assault] men (grenadiers, shotgunners), keeping those in [previous] where it can. The
         * machine gunners, snipers, the other riflemen and everyone else hold the head-on line and
         * fire. Empty when either group would be under [MIN_GROUP]: a fire team of three that
         * split sent two of them round a flank and left one to pin the point.
         */
        fun manoeuvreGroup(riflemen: Collection<UUID>, assault: Collection<UUID>, previous: Set<UUID>, size: Int): Set<UUID> {
            val sorted = riflemen.sortedWith(compareBy<UUID> { it !in previous }.thenBy { it.toString() })
            val group = sorted.take((sorted.size + 1) / 2) + assault
            return if (group.size >= MIN_GROUP && size - group.size >= MIN_GROUP) group.toSet() else emptySet()
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

/**
 * The least walking that puts each group into one of three sectors, no sector taking more than its
 * [room]; [walk] holds each group's walk to each sector. See [ApproachSectors.assign].
 */
private class SectorFill(private val room: List<Int>, private val walk: List<List<Double>>) {
    // least[i][a][b]: the least walking for groups i.. once a of the earlier ones took the first
    // sector and b the second; the rest of them took the third.
    private val least = Array(walk.size + 1) { Array(room[0] + 1) { DoubleArray(room[1] + 1) { Double.MAX_VALUE } } }

    init {
        for (a in 0..room[0]) {
            for (b in 0..room[1]) least[walk.size][a][b] = 0.0
        }
        for (index in walk.indices.reversed()) {
            for (a in 0..minOf(index, room[0])) {
                for (b in 0..minOf(index - a, room[1])) least[index][a][b] = walk[index].indices.minOf { next(index, a, b, it) }
            }
        }
    }

    /** Each group's sector, the earlier sector for the earlier group on a tie. */
    fun choose(): List<Int> {
        var a = 0
        var b = 0
        return walk.indices.map { index ->
            val sector = walk[index].indices.first { next(index, a, b, it) == least[index][a][b] }
            if (sector == 0) a++
            if (sector == 1) b++
            sector
        }
    }

    /** The walking from group [index] on, if it takes [sector] after a and b of the earlier ones took the first two. */
    private fun next(index: Int, a: Int, b: Int, sector: Int): Double {
        val na = if (sector == 0) a + 1 else a
        val nb = if (sector == 1) b + 1 else b
        if (!fits(na, nb, index + 1 - na - nb)) return Double.MAX_VALUE
        val rest = least[index + 1][na][nb]
        return if (rest == Double.MAX_VALUE) rest else walk[index][sector] + rest
    }

    private fun fits(first: Int, second: Int, third: Int) = first <= room[0] && second <= room[1] && third in 0..room[2]
}

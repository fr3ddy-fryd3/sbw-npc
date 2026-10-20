package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank

/**
 * How long a lone NPC takes to notice an enemy it has a clear line to (#114), and how far away it
 * never will.
 *
 * Time grows with the square of the distance and with the dark; both are set in the tables below,
 * so the feel is tuned here and nowhere else. The distances are seen by the cone and line-of-sight
 * checks first: this only decides how long a candidate that passed them takes to be noticed.
 */
object SpottingTime {
    /** A band of light at the target (0 to 15, [Light]) and what it costs. */
    enum class Light(val from: Int, val timeFactor: Double, val cutoff: Double) {
        BRIGHT(from = 12, timeFactor = 1.0, cutoff = 256.0),
        DIM(from = 9, timeFactor = 2.5, cutoff = 180.0),
        LOW(from = 6, timeFactor = 5.0, cutoff = 120.0),
        DARK(from = 3, timeFactor = 12.0, cutoff = 60.0),
        BLACK(from = 0, timeFactor = 25.0, cutoff = 30.0),
        ;

        companion object {
            fun of(level: Int): Light = entries.first { level >= it.from }
        }
    }

    private const val BASE_SECONDS = 0.2
    private const val DISTANCE_SECONDS = 0.3
    private const val REFERENCE_DISTANCE = 50.0

    /** At the edge of the cone the time is multiplied by this, from 1 straight ahead. */
    private const val CONE_EDGE_FACTOR = 2.0
    private const val CROUCHING_FACTOR = 1.5
    private const val SPRINTING_FACTOR = 0.7

    /** Every block of grass on the line multiplies the time by this. Three hide the target, see [DetectionSightline]. */
    private const val GRASS_FACTOR = 1.5

    /** Who is looking, and how well: the rank and class factors on the time and on the cut-off distance. */
    data class Observer(val timeFactor: Double = 1.0, val cutoffFactor: Double = 1.0) {
        companion object {
            fun of(rank: NpcRank, npcClass: NpcClass): Observer {
                val sniper = npcClass == NpcClass.SNIPER
                return Observer(
                    rankTime(rank) * (if (sniper) SNIPER_TIME else 1.0),
                    rankCutoff(rank) * (if (sniper) SNIPER_CUTOFF else 1.0),
                )
            }

            private const val SNIPER_TIME = 0.7
            private const val SNIPER_CUTOFF = 1.2

            private val RANK_TIME = mapOf(NpcRank.RECRUIT to 1.5, NpcRank.REGULAR to 1.0, NpcRank.VETERAN to 0.75, NpcRank.ELITE to 0.55)
            private val RANK_CUTOFF = mapOf(NpcRank.RECRUIT to 0.8, NpcRank.REGULAR to 1.0, NpcRank.VETERAN to 1.1, NpcRank.ELITE to 1.25)

            private fun rankTime(rank: NpcRank) = RANK_TIME.getValue(rank)

            private fun rankCutoff(rank: NpcRank) = RANK_CUTOFF.getValue(rank)
        }
    }

    /** What the observer can tell about one target. [offAxis] is 0 straight ahead to 1 at the cone's edge, see [Vision.offAxis]. */
    data class Target(val distance: Double, val light: Int, val offAxis: Double = 0.0, val crouching: Boolean = false, val sprinting: Boolean = false, val grassBlocks: Int = 0)

    /** Seconds the target takes to be noticed, or null when it is too far away for this light to ever be. */
    fun seconds(observer: Observer, target: Target): Double? {
        val light = Light.of(target.light.coerceIn(0, MAX_LIGHT))
        if (target.distance > light.cutoff * observer.cutoffFactor) return null
        val base = BASE_SECONDS + DISTANCE_SECONDS * (target.distance / REFERENCE_DISTANCE).let { it * it }
        var factor = light.timeFactor * observer.timeFactor * (1.0 + (CONE_EDGE_FACTOR - 1.0) * target.offAxis.coerceIn(0.0, 1.0))
        if (target.crouching) factor *= CROUCHING_FACTOR
        if (target.sprinting) factor *= SPRINTING_FACTOR
        repeat(target.grassBlocks.coerceAtLeast(0)) { factor *= GRASS_FACTOR }
        return base * factor
    }

    private const val MAX_LIGHT = 15
}

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
    /**
     * A band of light at the target (0 to 15, [Light]) and what it costs: to notice him, and to hit
     * him, [hitFactor] the share of hits left in this light (#155, see [DarkAim]).
     */
    enum class Light(val from: Int, val timeFactor: Double, val cutoff: Double, val hitFactor: Double) {
        // The owner's table (21.10): an elite sees 128 blocks in the brightest light and 32 in the
        // dark, each band about 1.4 times shorter than the one above. The times are a third to a
        // quarter of 20.10's in the dark bands, where 12x and 25x left a moonlit man 70 blocks off
        // unseen for eight seconds and a drone overhead for longer than its dive.
        BRIGHT(from = 12, timeFactor = 1.0 / SPEEDUP, cutoff = 128.0, hitFactor = 1.0),
        DIM(from = 9, timeFactor = 1.6 / SPEEDUP, cutoff = 90.0, hitFactor = 0.9),
        LOW(from = 6, timeFactor = 2.4 / SPEEDUP, cutoff = 64.0, hitFactor = 0.8),
        DARK(from = 3, timeFactor = 4.0 / SPEEDUP, cutoff = 45.0, hitFactor = 0.7),
        BLACK(from = 0, timeFactor = 6.0 / SPEEDUP, cutoff = 32.0, hitFactor = 0.6),
        ;

        companion object {
            fun of(level: Int): Light = entries.first { level >= it.from }
        }
    }

    /**
     * What the weather at the target costs on top of its light: a factor on the time and one on the
     * cut-off distance.
     *
     * Vanilla already darkens the sky in rain and more in a thunderstorm (raw light about 12 and 10
     * at noon), so a storm by day already falls into [Light.DIM]; [STORM] adds only what the light
     * does not. At night the darkening has nothing left to take, and these factors are all there is.
     */
    enum class Weather(val timeFactor: Double, val reachFactor: Double) {
        CLEAR(timeFactor = 1.0, reachFactor = 1.0),
        RAIN(timeFactor = 1.25, reachFactor = 0.8),
        SNOW(timeFactor = 1.4, reachFactor = 0.7),
        STORM(timeFactor = 1.25, reachFactor = 0.8),
    }

    /** What is being looked for: a drone is much smaller than a man, and is noticed later and nearer. */
    enum class Size(val timeFactor: Double, val reachFactor: Double) {
        PERSON(timeFactor = 1.0, reachFactor = 1.0),
        DRONE(timeFactor = 2.0, reachFactor = 0.6),
    }

    private const val SPEEDUP = 1.2

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
                    rankCutoff(rank) * classCutoff(npcClass),
                )
            }

            private const val SNIPER_TIME = 0.7
            private const val SNIPER_CUTOFF = 1.2
            private const val MORTAR_CUTOFF = 1.5
            private const val DRONE_OPERATOR_CUTOFF = 1.25

            /** Spotters with optics see further (owner's call, 21.10, for the mortar and the drone operator). */
            private fun classCutoff(npcClass: NpcClass): Double =
                when (npcClass) {
                    NpcClass.SNIPER -> SNIPER_CUTOFF
                    NpcClass.MORTAR, NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER -> MORTAR_CUTOFF
                    NpcClass.DRONE_OPERATOR -> DRONE_OPERATOR_CUTOFF
                    else -> 1.0
                }

            // The tables are an elite's; every rank below takes longer and sees less far (21.10).
            private val RANK_TIME = mapOf(NpcRank.RECRUIT to 2.0, NpcRank.REGULAR to 1.5, NpcRank.VETERAN to 1.25, NpcRank.ELITE to 1.0)
            private val RANK_CUTOFF = mapOf(NpcRank.RECRUIT to 0.7, NpcRank.REGULAR to 0.8, NpcRank.VETERAN to 0.9, NpcRank.ELITE to 1.0)

            private fun rankTime(rank: NpcRank) = RANK_TIME.getValue(rank)

            private fun rankCutoff(rank: NpcRank) = RANK_CUTOFF.getValue(rank)
        }
    }

    /** What the observer can tell about one target. [offAxis] is 0 straight ahead to 1 at the cone's edge, see [Vision.offAxis]. */
    data class Target(
        val distance: Double,
        val light: Int,
        val offAxis: Double = 0.0,
        val crouching: Boolean = false,
        val sprinting: Boolean = false,
        val grassBlocks: Int = 0,
        val weather: Weather = Weather.CLEAR,
        val size: Size = Size.PERSON,
    )

    /**
     * Seconds the target takes to be noticed, or null when it is too far away for this light to ever
     * be. With [cutOff] false there is no such distance, only a longer time.
     */
    fun seconds(observer: Observer, target: Target, cutOff: Boolean = true): Double? {
        val light = Light.of(target.light.coerceIn(0, MAX_LIGHT))
        if (cutOff && target.distance > light.cutoff * observer.cutoffFactor * target.weather.reachFactor * target.size.reachFactor) return null
        val base = BASE_SECONDS + DISTANCE_SECONDS * (target.distance / REFERENCE_DISTANCE).let { it * it }
        var factor = light.timeFactor * observer.timeFactor * target.weather.timeFactor * target.size.timeFactor *
            (1.0 + (CONE_EDGE_FACTOR - 1.0) * target.offAxis.coerceIn(0.0, 1.0))
        if (target.crouching) factor *= CROUCHING_FACTOR
        if (target.sprinting) factor *= SPRINTING_FACTOR
        repeat(target.grassBlocks.coerceAtLeast(0)) { factor *= GRASS_FACTOR }
        return base * factor
    }

    private const val MAX_LIGHT = 15
}

package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank

/**
 * How far from its objective a recon team takes its post, for the light and weather there and the
 * team's rank (#332): between the reach at which its scouts notice the defence and the reach at
 * which the defence notices a scout, so it sees without being seen. A fixed 72 blocks put it inside
 * the enemy's reach by day and beyond its own at night.
 *
 * [start] is its first post, [floor] the nearest it steps in to while it sees nobody, [step] how far
 * each step is.
 */
data class ReconReach(val start: Double, val floor: Double, val step: Double) {
    companion object {
        /** The first post is never farther than this: the post search and the march have to reach it. */
        const val MAX_START = 128.0

        /** Nor nearer than this, however dark: a post on the enemy is no post. */
        const val MIN_START = 24.0

        /** The steps in never go nearer than this. */
        const val MIN_FLOOR = 16.0

        /** Steps in are a fifth of the first post's distance, and never shorter than this. */
        const val MIN_STEP = 8.0

        private const val STEPS = 5.0

        /**
         * The reach for [light] (raw brightness 0..15) and [weather] at the objective and a team whose
         * lowest rank is [rank]. The enemy is taken as an elite rifleman: the post is chosen against the
         * best eyes it is likely to meet, not a sniper's.
         */
        fun of(light: Int, weather: SpottingTime.Weather, rank: NpcRank): ReconReach {
            val cutoff = SpottingTime.Light.of(light.coerceIn(0, MAX_LIGHT)).cutoff * weather.reachFactor
            val sees = cutoff * SpottingTime.Observer.of(rank, NpcClass.SCOUT).cutoffFactor
            val seen = cutoff * SpottingTime.Observer.of(NpcRank.ELITE, NpcClass.RIFLEMAN).cutoffFactor * SpottingTime.SCOUT_HIDDEN_REACH
            val start = ((sees + seen) / 2).coerceIn(MIN_START, MAX_START)
            return ReconReach(start, seen.coerceIn(MIN_FLOOR, start), maxOf(MIN_STEP, start / STEPS))
        }

        private const val MAX_LIGHT = 15
    }
}

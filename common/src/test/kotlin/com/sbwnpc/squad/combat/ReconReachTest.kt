package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconReachTest {
    @Test fun `by day the post lies out of the enemy's reach on a scout and within the scout's own`() {
        val reach = ReconReach.of(15, SpottingTime.Weather.CLEAR, NpcRank.REGULAR)
        // A regular scout sees 154 blocks by day, an elite rifleman notices a scout at 102.
        assertEquals(128.0, reach.start, 1e-9)
        assertEquals(102.4, reach.floor, 1e-9)
        assertEquals(25.6, reach.step, 1e-9)
    }

    @Test fun `in the dark it goes in close, and never nearer than the enemy notices a scout`() {
        val reach = ReconReach.of(0, SpottingTime.Weather.CLEAR, NpcRank.REGULAR)
        // 38.4 seen by the scout, 25.6 by the enemy.
        assertEquals(32.0, reach.start, 1e-9)
        assertEquals(25.6, reach.floor, 1e-9)
        assertEquals(8.0, reach.step, 1e-9)
    }

    @Test fun `rain and a raw recruit bring the post in`() {
        val clear = ReconReach.of(10, SpottingTime.Weather.CLEAR, NpcRank.ELITE)
        val rain = ReconReach.of(10, SpottingTime.Weather.RAIN, NpcRank.ELITE)
        val recruit = ReconReach.of(10, SpottingTime.Weather.CLEAR, NpcRank.RECRUIT)
        assertTrue(rain.start < clear.start)
        assertTrue(recruit.start < clear.start)
        assertTrue(recruit.floor <= recruit.start)
    }
}

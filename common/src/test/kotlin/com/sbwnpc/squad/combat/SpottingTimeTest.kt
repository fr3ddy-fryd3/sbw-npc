package com.sbwnpc.squad.combat

import com.sbwnpc.squad.combat.SpottingTime.Observer
import com.sbwnpc.squad.combat.SpottingTime.Target
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpottingTimeTest {
    private val regular = Observer()

    /** Every band is 20% quicker than the table's base seconds, and the night bands reach 20% further. */
    private val day = 1.0 / 1.2

    private fun seconds(distance: Double, light: Int, observer: Observer = regular) = SpottingTime.seconds(observer, Target(distance, light))

    @Test fun `by day the table's seconds hold at 25, 50 and 100 blocks`() {
        assertEquals(0.275 * day, seconds(25.0, 15)!!, 1e-9)
        assertEquals(0.5 * day, seconds(50.0, 15)!!, 1e-9)
        assertEquals(1.4 * day, seconds(100.0, 15)!!, 1e-9)
    }

    @Test fun `each darker band takes longer and sees less far`() {
        assertEquals(0.5 * 2.5 * day, seconds(50.0, 10)!!, 1e-9)
        assertEquals(0.5 * 5.0 * day, seconds(50.0, 7)!!, 1e-9)
        assertEquals(0.5 * 12.0 * day, seconds(50.0, 4)!!, 1e-9)
        assertNull(seconds(50.0, 1))
        assertNull(seconds(73.0, 4))
        assertNotNull(seconds(72.0, 4))
        assertNull(seconds(37.0, 0))
    }

    @Test fun `light is clamped to the 0 to 15 scale`() {
        assertEquals(seconds(50.0, 15), seconds(50.0, 99))
        assertEquals(seconds(20.0, 0), seconds(20.0, -4))
    }

    @Test fun `rank shortens the time and lengthens the cut-off`() {
        val recruit = Observer.of(NpcRank.RECRUIT, NpcClass.RIFLEMAN)
        val elite = Observer.of(NpcRank.ELITE, NpcClass.RIFLEMAN)
        assertEquals(1.4 * day * 1.5, seconds(100.0, 15, recruit)!!, 1e-9)
        assertEquals(1.4 * day * 0.55, seconds(100.0, 15, elite)!!, 1e-9)
        // Dark cut-off: 72 for a regular, 57.6 for a recruit, 90 for an elite.
        assertNull(seconds(58.0, 4, recruit))
        assertNotNull(seconds(57.0, 4, recruit))
        assertNotNull(seconds(90.0, 4, elite))
        assertNull(seconds(91.0, 4, elite))
    }

    @Test fun `a sniper is quicker and sees further than the same rank of rifleman`() {
        val sniper = Observer.of(NpcRank.ELITE, NpcClass.SNIPER)
        assertEquals(1.4 * day * 0.55 * 0.7, seconds(100.0, 15, sniper)!!, 1e-9)
        assertNotNull(seconds(108.0, 4, sniper))
        assertNull(seconds(109.0, 4, sniper))
    }

    @Test fun `the cone's edge, a crouching target and a sprinting one scale the time`() {
        val base = seconds(50.0, 15)!!
        assertEquals(base * 2.0, SpottingTime.seconds(regular, Target(50.0, 15, offAxis = 1.0))!!, 1e-9)
        assertEquals(base * 1.5, SpottingTime.seconds(regular, Target(50.0, 15, offAxis = 0.5))!!, 1e-9)
        assertEquals(base * 1.5, SpottingTime.seconds(regular, Target(50.0, 15, crouching = true))!!, 1e-9)
        assertEquals(base * 0.7, SpottingTime.seconds(regular, Target(50.0, 15, sprinting = true))!!, 1e-9)
    }

    @Test fun `each block of grass on the line takes half as long again`() {
        val base = seconds(50.0, 15)!!
        assertEquals(base * 1.5, SpottingTime.seconds(regular, Target(50.0, 15, grassBlocks = 1))!!, 1e-9)
        assertEquals(base * 2.25, SpottingTime.seconds(regular, Target(50.0, 15, grassBlocks = 2))!!, 1e-9)
    }

    @Test fun `a recruit in the dark needs six seconds for a man 40 blocks away and cannot see one at 58`() {
        val recruit = Observer.of(NpcRank.RECRUIT, NpcClass.RIFLEMAN)
        assertEquals((0.2 + 0.3 * 0.8 * 0.8) * 12.0 * day * 1.5, seconds(40.0, 4, recruit)!!, 1e-9)
        assertTrue(seconds(58.0, 4, recruit) == null)
    }
    @Test fun `rain, snow and a storm each take longer and see less far than clear weather`() {
        val clear = seconds(50.0, 15)!!
        for (weather in listOf(SpottingTime.Weather.RAIN, SpottingTime.Weather.SNOW, SpottingTime.Weather.STORM)) {
            assertEquals(clear * weather.timeFactor, SpottingTime.seconds(regular, Target(50.0, 15, weather = weather))!!, 1e-9, weather.name)
        }
        // Dark band: 60 * 1.2 = 72 blocks in clear weather, 72 * 0.7 = 50.4 in rain and 43.2 in snow.
        assertNotNull(SpottingTime.seconds(regular, Target(50.0, 4, weather = SpottingTime.Weather.RAIN)))
        assertNull(SpottingTime.seconds(regular, Target(51.0, 4, weather = SpottingTime.Weather.RAIN)))
        assertNull(SpottingTime.seconds(regular, Target(44.0, 4, weather = SpottingTime.Weather.SNOW)))
    }

    @Test fun `a drone takes three times as long as a man and is seen half as far`() {
        val man = seconds(30.0, 15)!!
        assertEquals(man * 3.0, SpottingTime.seconds(regular, Target(30.0, 15, size = SpottingTime.Size.DRONE))!!, 1e-9)
        // Dark band at night: a man at 72 blocks, a drone only to 36.
        assertNotNull(SpottingTime.seconds(regular, Target(36.0, 4, size = SpottingTime.Size.DRONE)))
        assertNull(SpottingTime.seconds(regular, Target(37.0, 4, size = SpottingTime.Size.DRONE)))
    }
}

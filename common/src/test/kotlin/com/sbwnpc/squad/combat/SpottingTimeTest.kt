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
    /** The tables are an elite's: his factors are 1. */
    private val elite = Observer()

    /** Every band is 20% quicker than the table's base seconds. */
    private val day = 1.0 / 1.2

    private fun seconds(distance: Double, light: Int, observer: Observer = elite) = SpottingTime.seconds(observer, Target(distance, light))

    @Test fun `by day the table's seconds hold at 25, 50 and 100 blocks`() {
        assertEquals(0.275 * day, seconds(25.0, 15)!!, 1e-9)
        assertEquals(0.5 * day, seconds(50.0, 15)!!, 1e-9)
        assertEquals(1.4 * day, seconds(100.0, 15)!!, 1e-9)
    }

    @Test fun `each darker band takes longer and sees less far`() {
        val base = 0.2 + 0.3 * 0.8 * 0.8 // 40 blocks
        assertEquals(base * 1.6 * day, seconds(40.0, 10)!!, 1e-9)
        assertEquals(base * 2.4 * day, seconds(40.0, 7)!!, 1e-9)
        assertEquals(base * 4.0 * day, seconds(40.0, 4)!!, 1e-9)
        assertEquals(base * 6.0 * day, seconds(30.0, 0)!! / (0.2 + 0.3 * 0.6 * 0.6) * base, 1e-9)
        assertNotNull(seconds(128.0, 15))
        assertNull(seconds(129.0, 15))
        assertNull(seconds(91.0, 10))
        assertNull(seconds(65.0, 7))
        assertNotNull(seconds(45.0, 4))
        assertNull(seconds(46.0, 4))
        assertNotNull(seconds(32.0, 0))
        assertNull(seconds(33.0, 0))
    }

    @Test fun `light is clamped to the 0 to 15 scale`() {
        assertEquals(seconds(50.0, 15), seconds(50.0, 99))
        assertEquals(seconds(20.0, 0), seconds(20.0, -4))
    }

    @Test fun `each rank below elite takes longer and sees less far`() {
        assertEquals(Observer(), Observer.of(NpcRank.ELITE, NpcClass.RIFLEMAN))
        val regular = Observer.of(NpcRank.REGULAR, NpcClass.RIFLEMAN)
        val recruit = Observer.of(NpcRank.RECRUIT, NpcClass.RIFLEMAN)
        assertEquals(1.4 * day * 1.5, seconds(100.0, 15, regular)!!, 1e-9)
        assertEquals(0.5 * day * 2.0, seconds(50.0, 15, recruit)!!, 1e-9)
        // Bright cut-off: 128 for an elite, 102.4 for a regular, 89.6 for a recruit.
        assertNotNull(seconds(102.0, 15, regular))
        assertNull(seconds(103.0, 15, regular))
        assertNull(seconds(90.0, 15, recruit))
        // Dark: 45 for an elite, 36 for a regular.
        assertNotNull(seconds(36.0, 4, regular))
        assertNull(seconds(37.0, 4, regular))
    }

    @Test fun `a sniper is quicker and sees further than the same rank of rifleman`() {
        val sniper = Observer.of(NpcRank.ELITE, NpcClass.SNIPER)
        assertEquals(1.4 * day * 0.7, seconds(100.0, 15, sniper)!!, 1e-9)
        // Dark: 45 * 1.2 = 54.
        assertNotNull(seconds(54.0, 4, sniper))
        assertNull(seconds(55.0, 4, sniper))
    }

    @Test fun `the cone's edge, a crouching target and a sprinting one scale the time`() {
        val base = seconds(50.0, 15)!!
        assertEquals(base * 2.0, SpottingTime.seconds(elite, Target(50.0, 15, offAxis = 1.0))!!, 1e-9)
        assertEquals(base * 1.5, SpottingTime.seconds(elite, Target(50.0, 15, offAxis = 0.5))!!, 1e-9)
        assertEquals(base * 1.5, SpottingTime.seconds(elite, Target(50.0, 15, crouching = true))!!, 1e-9)
        assertEquals(base * 0.7, SpottingTime.seconds(elite, Target(50.0, 15, sprinting = true))!!, 1e-9)
    }

    @Test fun `each block of grass on the line takes half as long again`() {
        val base = seconds(50.0, 15)!!
        assertEquals(base * 1.5, SpottingTime.seconds(elite, Target(50.0, 15, grassBlocks = 1))!!, 1e-9)
        assertEquals(base * 2.25, SpottingTime.seconds(elite, Target(50.0, 15, grassBlocks = 2))!!, 1e-9)
    }

    @Test fun `a recruit in the dark needs two seconds for a man 30 blocks away and cannot see one at 32`() {
        val recruit = Observer.of(NpcRank.RECRUIT, NpcClass.RIFLEMAN)
        assertEquals((0.2 + 0.3 * 0.6 * 0.6) * 4.0 * day * 2.0, seconds(30.0, 4, recruit)!!, 1e-9)
        assertTrue(seconds(32.0, 4, recruit) == null)
    }

    @Test fun `rain, snow and a storm each take longer and see less far than clear weather`() {
        val clear = seconds(50.0, 15)!!
        for (weather in listOf(SpottingTime.Weather.RAIN, SpottingTime.Weather.SNOW, SpottingTime.Weather.STORM)) {
            assertEquals(clear * weather.timeFactor, SpottingTime.seconds(elite, Target(50.0, 15, weather = weather))!!, 1e-9, weather.name)
        }
        // Dark band: 45 blocks in clear weather, 36 in rain and 31.5 in snow.
        assertNotNull(SpottingTime.seconds(elite, Target(36.0, 4, weather = SpottingTime.Weather.RAIN)))
        assertNull(SpottingTime.seconds(elite, Target(37.0, 4, weather = SpottingTime.Weather.RAIN)))
        assertNull(SpottingTime.seconds(elite, Target(32.0, 4, weather = SpottingTime.Weather.SNOW)))
    }

    @Test fun `a drone takes twice as long as a man and is seen 60 percent as far`() {
        val man = seconds(30.0, 15)!!
        assertEquals(man * 2.0, SpottingTime.seconds(elite, Target(30.0, 15, size = SpottingTime.Size.DRONE))!!, 1e-9)
        // Dark band at night: a man to 45 blocks, a drone only to 27.
        assertNotNull(SpottingTime.seconds(elite, Target(27.0, 4, size = SpottingTime.Size.DRONE)))
        assertNull(SpottingTime.seconds(elite, Target(28.0, 4, size = SpottingTime.Size.DRONE)))
    }

    @Test fun `the mortar crew sees half as far again and the drone operator a quarter`() {
        assertNotNull(seconds(192.0, 15, Observer.of(NpcRank.ELITE, NpcClass.MORTAR_OPERATOR)))
        assertNull(seconds(193.0, 15, Observer.of(NpcRank.ELITE, NpcClass.MORTAR)))
        assertNotNull(seconds(160.0, 15, Observer.of(NpcRank.ELITE, NpcClass.DRONE_OPERATOR)))
        assertNull(seconds(161.0, 15, Observer.of(NpcRank.ELITE, NpcClass.DRONE_OPERATOR)))
    }
}

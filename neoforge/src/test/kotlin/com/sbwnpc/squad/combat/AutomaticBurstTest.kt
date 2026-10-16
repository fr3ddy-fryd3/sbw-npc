package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.util.RandomSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutomaticBurstTest {
    @Test fun `catch up shots in one tick stop at a role specific burst and wait before resuming`() {
        for ((role, rounds, pause) in listOf(
            Triple(NpcClass.RIFLEMAN, 3..5, 20..35),
            Triple(NpcClass.MACHINE_GUNNER, 6..8, 12..22),
        )) {
            val burst = AutomaticBurst()
            val random = RandomSource.create(47L)
            var tick = 100
            repeat(20) {
                var shots = 0
                while (!burst.paused(tick) && shots < 20) {
                    burst.fired(role, tick, random)
                    shots++
                }
                assertTrue(shots in rounds, "$role fired $shots")
                val resumed = (1..40).first { !burst.paused(tick + it) }
                assertTrue(resumed in pause)
                assertTrue(burst.paused(tick + resumed - 1))
                tick += resumed
            }
        }
    }

    @Test fun `an aim interruption does not refill the burst but ending combat resets it`() {
        val burst = AutomaticBurst()
        val random = RandomSource.create(2L)
        burst.fired(NpcClass.RIFLEMAN, 0, random)
        repeat(20) { assertFalse(burst.paused(it)) }
        var following = 0
        while (!burst.paused(20) && following < 10) {
            burst.fired(NpcClass.RIFLEMAN, 20, random)
            following++
        }
        assertTrue(following in 2..4)
        burst.reset()
        assertFalse(burst.paused(20))
    }
}

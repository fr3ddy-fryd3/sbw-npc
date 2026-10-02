package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GroundTravelProgressTest {
    @Test
    fun `a 1500 block trip stays mounted beyond the former two minute limit`() {
        val progress = GroundTravelProgress()
        progress.reset(0, Vec3.ZERO)
        for (tick in 1..6000) {
            progress.observe(tick, Vec3(tick / 4.0, 64.0, 0.0))
            assertFalse(progress.stalled(tick), "trip abandoned at tick $tick")
        }
    }

    @Test
    fun `oscillation across tile boundaries is eventually treated as stuck`() {
        val progress = GroundTravelProgress()
        progress.reset(0, Vec3.ZERO)
        for (tick in 1..2400) progress.observe(tick, Vec3(if (tick % 80 < 40) -5.0 else 5.0, 64.0, 0.0))
        assertTrue(progress.stalled(2400))
    }

    @Test
    fun `terrain detours count and a new trip resets its stalled state`() {
        val progress = GroundTravelProgress()
        progress.reset(0, Vec3.ZERO)
        for (tick in 1..3000) progress.observe(tick, Vec3(-tick / 4.0, 64.0, tick / 8.0))
        assertFalse(progress.stalled(3000))
        assertTrue(progress.stalled(4301))
        progress.reset(4301, Vec3.ZERO)
        assertFalse(progress.stalled(4301))
    }
}

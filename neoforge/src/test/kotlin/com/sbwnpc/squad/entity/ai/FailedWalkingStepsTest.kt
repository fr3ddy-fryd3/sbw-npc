package com.sbwnpc.squad.entity.ai

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FailedWalkingStepsTest {
    @Test fun `failed upward step leaves other approaches and reverse travel open and expires`() {
        val failures = FailedWalkingSteps()
        val start = BlockPos(0, 64, 0)
        val step = BlockPos(1, 65, 0)
        failures.reject(start, step, 20)
        assertFalse(failures.permits(start, step, 219))
        assertTrue(failures.permits(step, start, 21))
        assertTrue(failures.permits(BlockPos(1, 64, 1), step, 21))
        assertTrue(failures.permits(start, step, 220))
    }
}

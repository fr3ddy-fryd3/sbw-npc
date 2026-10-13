package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FiringObstructionTest {
    @Test fun `brief clear glimpses allow aiming but do not erase the blocked post history`() {
        val obstruction = FiringObstruction()
        repeat(100) { obstruction.observe(false) }
        assertTrue(obstruction.requiresRecovery)
        obstruction.observe(true)
        assertFalse(obstruction.requiresRecovery, "A clear current view must have time to aim")
        obstruction.observe(false)
        assertTrue(obstruction.requiresRecovery, "One unproductive glimpse erased sustained obstruction")
        obstruction.reset()
        assertFalse(obstruction.requiresRecovery, "Actual fire did not acknowledge a usable post")
    }

    @Test fun `ordinary initial aim and extended clear sight do not request recovery`() {
        val obstruction = FiringObstruction()
        repeat(60) { obstruction.observe(false) }
        assertFalse(obstruction.requiresRecovery)
        repeat(200) { obstruction.observe(true) }
        assertFalse(obstruction.requiresRecovery)
        assertEquals(0, obstruction.ticks)
    }
}

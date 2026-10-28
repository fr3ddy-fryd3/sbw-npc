package com.sbwnpc.squad.entity.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BeyondRangeGraceTest {
    @Test fun `a target in range never runs out`() {
        val grace = BeyondRangeGrace(200)
        assertFalse(grace.expired(inRange = true, now = 0))
        assertFalse(grace.expired(inRange = true, now = 10_000))
    }

    @Test fun `a target out of range is kept for the grace period and then let go`() {
        val grace = BeyondRangeGrace(200)
        assertFalse(grace.expired(inRange = false, now = 100))
        assertFalse(grace.expired(inRange = false, now = 299))
        assertTrue(grace.expired(inRange = false, now = 300))
    }

    @Test fun `coming back into range restarts the count`() {
        val grace = BeyondRangeGrace(200)
        assertFalse(grace.expired(inRange = false, now = 0))
        assertFalse(grace.expired(inRange = true, now = 150))
        assertFalse(grace.expired(inRange = false, now = 250))
        assertTrue(grace.expired(inRange = false, now = 450))
    }
}

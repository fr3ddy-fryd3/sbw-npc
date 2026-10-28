package com.sbwnpc.squad.entity.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BeyondRangeGraceTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)

    @Test fun `a target in range never runs out`() {
        val grace = BeyondRangeGrace(200)
        assertFalse(grace.expired(a, inRange = true, now = 0))
        assertFalse(grace.expired(a, inRange = true, now = 10_000))
    }

    @Test fun `no target never runs out`() {
        assertFalse(BeyondRangeGrace(200).expired(null, inRange = false, now = 10_000))
    }

    @Test fun `a target out of range is kept for the grace period and then let go`() {
        val grace = BeyondRangeGrace(200)
        assertFalse(grace.expired(a, inRange = false, now = 100))
        assertFalse(grace.expired(a, inRange = false, now = 299))
        assertTrue(grace.expired(a, inRange = false, now = 300))
    }

    @Test fun `coming back into range restarts the count`() {
        val grace = BeyondRangeGrace(200)
        assertFalse(grace.expired(a, inRange = false, now = 0))
        assertFalse(grace.expired(a, inRange = true, now = 150))
        assertFalse(grace.expired(a, inRange = false, now = 250))
        assertTrue(grace.expired(a, inRange = false, now = 450))
    }

    @Test fun `a different far target starts its own count`() {
        val grace = BeyondRangeGrace(200)
        assertFalse(grace.expired(a, inRange = false, now = 0))
        assertTrue(grace.expired(a, inRange = false, now = 250))
        assertFalse(grace.expired(b, inRange = false, now = 260))
        assertTrue(grace.expired(b, inRange = false, now = 460))
    }
}

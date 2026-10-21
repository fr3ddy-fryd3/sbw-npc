package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaryApproachTest {
    @Test fun `an approach stays wary until the first contact since the order, then for good`() {
        val latch = WaryApproach.Latch()
        assertTrue(latch.update(1, 100) { false })
        assertEquals(100, latch.since)
        assertFalse(latch.update(1, 140) { true })
        // A lull after contact does not bring the walk back.
        assertFalse(latch.update(1, 400) { false })
    }

    @Test fun `contact is counted only from the order's first tick`() {
        val latch = WaryApproach.Latch()
        latch.update(1, 100) { false }
        var askedSince = -1L
        latch.update(1, 120) { since ->
            askedSince = since
            false
        }
        assertEquals(100, askedSince)
    }

    @Test fun `a new order starts a new wary approach`() {
        val latch = WaryApproach.Latch()
        latch.update(1, 100) { true }
        assertTrue(latch.update(2, 500) { false })
        assertEquals(500, latch.since)
    }
}

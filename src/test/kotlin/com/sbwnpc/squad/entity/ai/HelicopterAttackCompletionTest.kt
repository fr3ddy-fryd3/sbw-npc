package com.sbwnpc.squad.entity.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HelicopterAttackCompletionTest {
    @Test fun `an empty attack completes after the quiet interval without any prior enemy`() {
        val completion = HelicopterAttackCompletion()
        assertFalse(completion.observe(1, 1000, false))
        assertFalse(completion.observe(1, 1199, false))
        assertTrue(completion.observe(1, 1200, false))
        assertFalse(completion.observe(2, 2000, false))
        assertFalse(completion.observe(2, 2199, false))
        assertTrue(completion.observe(2, 2200, false))
    }

    @Test fun `attack completion waits for sustained quiet and resets on a new order`() {
        val completion = HelicopterAttackCompletion()
        assertFalse(completion.observe(1, 1000, false), "A new attack starts its own quiet interval")
        assertFalse(completion.observe(1, 2000, true))
        assertFalse(completion.observe(1, 2199, false))
        assertFalse(completion.observe(1, 2199, true), "New contact restarts the quiet interval")
        assertFalse(completion.observe(1, 2398, false))
        assertTrue(completion.observe(1, 2399, false))
        assertFalse(completion.observe(2, 3000, false), "Previous attack contact cannot complete a new command")
    }
}

package com.sbwnpc.squad.entity.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HelicopterAttackCompletionTest {
    @Test fun `attack completion waits for contact then sustained quiet and resets on a new order`() {
        val completion = HelicopterAttackCompletion()
        assertFalse(completion.observe(1, 1000, false), "An empty objective is still an attack orbit")
        assertFalse(completion.observe(1, 2000, true))
        assertFalse(completion.observe(1, 2199, false))
        assertFalse(completion.observe(1, 2199, true), "New contact restarts the quiet interval")
        assertFalse(completion.observe(1, 2398, false))
        assertTrue(completion.observe(1, 2399, false))
        assertFalse(completion.observe(2, 3000, false), "Previous attack contact cannot complete a new command")
    }
}

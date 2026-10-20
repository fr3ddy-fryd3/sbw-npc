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

    @Test fun `only an aircraft that could see the objective surveys it`() {
        val objective = net.minecraft.world.phys.Vec3(0.0, 64.0, 0.0)
        assertTrue(HelicopterAttackCompletion.surveys(objective.add(40.0, 36.0, 0.0), objective), "AH-6 orbit in the top altitude band")
        assertTrue(HelicopterAttackCompletion.surveys(objective.add(70.0, 14.0, 0.0), objective), "Mi-28 loitering round it")
        assertTrue(HelicopterAttackCompletion.surveys(objective.add(40.0, 60.0, 0.0), objective), "Orbit over a valley rim 24 above the objective")
        assertFalse(HelicopterAttackCompletion.surveys(objective.add(0.0, 130.0, 0.0), objective), "Held at ridge height over the objective (#119)")
    }
}

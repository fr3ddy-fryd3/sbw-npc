package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PostRequirementsTest {
    @Test fun `nothing is required off fire`() {
        CombatDuty.entries.forEach { duty ->
            assertEquals(false, PostRequirements.cover(duty, incoming = false), "cover for $duty")
            assertEquals(false, PostRequirements.shelter(duty, incoming = false), "shelter for $duty")
        }
    }

    @Test fun `under fire a mover needs cover and shelter, a shooter shelter only`() {
        val cover = CombatDuty.entries.filter { PostRequirements.cover(it, incoming = true) }
        val shelter = CombatDuty.entries.filter { PostRequirements.shelter(it, incoming = true) }
        assertEquals(listOf(CombatDuty.MOVE), cover)
        assertEquals(listOf(CombatDuty.FIRE, CombatDuty.MOVE), shelter)
    }
}

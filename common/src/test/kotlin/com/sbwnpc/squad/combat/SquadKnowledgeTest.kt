package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SquadKnowledgeTest {
    @Test fun `an enemy nobody has noticed is not known`() {
        assertFalse(SquadKnowledge.known(seenAt = null, now = 1_000))
    }

    @Test fun `an enemy a squadmate noticed is known for the length of the contact memory`() {
        assertTrue(SquadKnowledge.known(seenAt = 1_000, now = 1_000))
        assertTrue(SquadKnowledge.known(seenAt = 1_000, now = 1_000 + SquadKnowledge.KNOWN_TICKS))
        assertFalse(SquadKnowledge.known(seenAt = 1_000, now = 1_000 + SquadKnowledge.KNOWN_TICKS + 1))
    }

    @Test fun `the memory is the same ten seconds the squad's tactical contacts keep`() {
        assertEquals(200L, SquadKnowledge.KNOWN_TICKS)
    }
}

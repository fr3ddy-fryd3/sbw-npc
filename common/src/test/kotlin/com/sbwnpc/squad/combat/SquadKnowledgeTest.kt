package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SquadKnowledgeTest {
    @Test fun `an enemy nobody has noticed is not known`() {
        assertFalse(SquadKnowledge.known(seenAt = null, firstSeenAt = null, now = 1_000))
    }

    @Test fun `a squadmate hears of an enemy only after the call has been made and understood`() {
        assertFalse(SquadKnowledge.known(seenAt = 1_000, firstSeenAt = 1_000, now = 1_000))
        assertFalse(SquadKnowledge.known(seenAt = 1_029, firstSeenAt = 1_000, now = 1_029))
        assertTrue(SquadKnowledge.known(seenAt = 1_030, firstSeenAt = 1_000, now = 1_030))
    }

    @Test fun `the call takes a second and a half in all, one to say it and half a second for everyone to take it in`() {
        assertEquals(30L, SquadKnowledge.SHARE_TICKS)
    }

    @Test fun `an enemy the first man keeps seeing is not told again each time`() {
        // Refreshed every tick for ten seconds: the delay counts from the first sighting, not the last.
        assertTrue(SquadKnowledge.known(seenAt = 1_200, firstSeenAt = 1_000, now = 1_200))
    }

    @Test fun `an enemy is known for the length of the contact memory after the last sighting`() {
        assertTrue(SquadKnowledge.known(seenAt = 1_000, firstSeenAt = 900, now = 1_000 + SquadKnowledge.KNOWN_TICKS))
        assertFalse(SquadKnowledge.known(seenAt = 1_000, firstSeenAt = 900, now = 1_000 + SquadKnowledge.KNOWN_TICKS + 1))
    }

    @Test fun `a sighting after the memory has lapsed starts the call afresh`() {
        assertEquals(5_000L, SquadKnowledge.firstSeenAt(oldSeenAt = 1_000, oldFirstSeenAt = 900, now = 5_000))
        assertEquals(900L, SquadKnowledge.firstSeenAt(oldSeenAt = 4_900, oldFirstSeenAt = 900, now = 5_000))
        assertEquals(5_000L, SquadKnowledge.firstSeenAt(oldSeenAt = null, oldFirstSeenAt = null, now = 5_000))
    }

    @Test fun `the memory is the same ten seconds the squad's tactical contacts keep`() {
        assertEquals(200L, SquadKnowledge.KNOWN_TICKS)
    }
}

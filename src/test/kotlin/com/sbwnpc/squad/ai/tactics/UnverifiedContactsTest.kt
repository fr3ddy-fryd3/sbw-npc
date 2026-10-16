package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnverifiedContactsTest {
    @Test fun `a position away from the objective stops holding it open after 30 s unseen`() {
        // In-game 16.10: one position 89 blocks away, no route to it, kept the attack open 6 min.
        assertFalse(UnverifiedContacts.expired(89.0, 600))
        assertTrue(UnverifiedContacts.expired(89.0, 601))
    }

    @Test fun `a position at the objective is checked for longer`() {
        assertFalse(UnverifiedContacts.expired(20.0, 601))
        assertTrue(UnverifiedContacts.expired(20.0, 2401))
    }
}

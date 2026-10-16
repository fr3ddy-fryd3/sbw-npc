package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test fun `scouts check the nearest remembered position within reach of the point`() {
        val point = Point3(193.0, 102.0, -173.0)
        val far = Point3(191.0, 88.0, -84.0)
        val near = Point3(200.0, 100.0, -140.0)
        assertEquals(near, UnverifiedContacts.scoutTarget(point, listOf(far, near)))
        // In-game 16.10: the only remembered position was 89 blocks out; no scout goes there.
        assertNull(UnverifiedContacts.scoutTarget(point, listOf(far)))
    }
}

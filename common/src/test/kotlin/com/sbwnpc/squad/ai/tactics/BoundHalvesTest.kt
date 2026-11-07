package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BoundHalvesTest {
    private val men = (1..6).map { UUID(0, it.toLong()) }

    @Test fun `without heavy guns the halves alternate along the front`() {
        assertEquals(setOf(men[0], men[2], men[4]), BoundHalves.firstMoving(men, emptySet()))
        assertEquals(setOf(men[0], men[2]), BoundHalves.firstMoving(men.take(4), emptySet()))
    }

    @Test fun `a lone heavy gun fires while the first half rushes`() {
        val moving = BoundHalves.firstMoving(men, setOf(men[0]))
        assertFalse(men[0] in moving)
        assertEquals(3, moving.size)
    }

    @Test fun `two heavy guns stand in different halves and the halves stay even`() {
        listOf(setOf(men[0], men[2]), setOf(men[1], men[3]), setOf(men[4], men[5])).forEach { heavy ->
            val moving = BoundHalves.firstMoving(men, heavy)
            assertEquals(1, heavy.count { it in moving }, "heavy=$heavy moving=$moving")
            assertEquals(3, moving.size)
        }
    }

    @Test fun `a squad of heavy guns alone still rushes its larger half first`() {
        assertEquals(setOf(men[0]), BoundHalves.firstMoving(men.take(1), men.toSet()))
        assertEquals(2, BoundHalves.firstMoving(men.take(3), men.toSet()).size)
    }

    @Test fun `an odd squad moves the larger half first and keeps every man in one half`() {
        val five = men.take(5)
        val moving = BoundHalves.firstMoving(five, setOf(five[1], five[3]))
        assertEquals(3, moving.size)
        assertTrue(moving.all { it in five })
        assertEquals(1, setOf(five[1], five[3]).count { it in moving })
    }
}

package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.tactics.Engage
import com.sbwnpc.squad.combat.FireObservation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** The plan reads what his gun saw as flags, so a round fired does not repair his plan (#251 step B). */
class EngageFactsTest {
    private val target = UUID.randomUUID()

    @Test fun `the opening stays due through every round of the burst and ends once, on the last`() {
        val seen = FireObservation()
        seen.look(target, 0, shootable = true, automaticWeapon = true, stamp = 1)
        val due = (0 until Engage.OPENING_ROUNDS_AUTOMATIC).map { EngageFacts.openingDue(seen, target, 10).also { seen.fired() } }
        assertEquals(List(Engage.OPENING_ROUNDS_AUTOMATIC) { true }, due)
        assertFalse(EngageFacts.openingDue(seen, target, 10))
    }

    @Test fun `the opening ends with its window, and is never due for another target`() {
        val seen = FireObservation()
        seen.look(target, 0, shootable = true, automaticWeapon = false, stamp = 1)
        assertTrue(EngageFacts.openingDue(seen, target, Engage.OPENING_WINDOW - 1))
        assertFalse(EngageFacts.openingDue(seen, target, Engage.OPENING_WINDOW))
        assertFalse(EngageFacts.openingDue(seen, UUID.randomUUID(), 1))
    }

    @Test fun `a man walking a block back and forth at the edge of a band flips it once`() {
        val limit = 40.0
        var near = false
        val seen = ArrayList<Boolean>()
        for (step in 0 until 40) {
            val distance = limit + 1.0 + if (step % 2 == 0) 0.5 else -0.5
            near = EngageFacts.band(near, if (step < 2) limit - 1.0 else distance, limit, within = true)
            seen += near
        }
        assertEquals(0, seen.zipWithNext().drop(1).count { (a, b) -> a != b }, "$seen")
        assertTrue(seen.last())
    }
}

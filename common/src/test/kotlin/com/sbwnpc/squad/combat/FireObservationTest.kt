package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** What a man's gun saw, as his engage facts read it (#251 step B). */
class FireObservationTest {
    private val target = UUID.randomUUID()

    @Test fun `a hidden contact starts the opening window only when first shootable`() {
        val seen = FireObservation()
        seen.look(target, 0, shootable = false, automaticWeapon = true, stamp = 1)
        assertNull(seen.firstShootableAt)
        seen.look(target, 200, shootable = true, automaticWeapon = true, stamp = 1)
        seen.look(target, 255, shootable = true, automaticWeapon = true, stamp = 1)
        assertEquals(200L, seen.firstShootableAt)
        assertEquals(255L, seen.clearShotAt)
    }

    @Test fun `a new contact or a new order gets its own opening fire`() {
        val seen = FireObservation()
        seen.look(target, 0, shootable = true, automaticWeapon = true, stamp = 1)
        repeat(4) { seen.fired() }
        seen.look(UUID.randomUUID(), 10, shootable = true, automaticWeapon = true, stamp = 1)
        assertEquals(0, seen.shots)
        assertEquals(10L, seen.firstShootableAt)
        repeat(2) { seen.fired() }
        seen.look(seen.target!!, 20, shootable = true, automaticWeapon = true, stamp = 2)
        assertEquals(0, seen.shots)
        assertEquals(20L, seen.firstShootableAt)
    }

    @Test fun `lane checks count blocks in a row, by kind, and one clear check ends them`() {
        val seen = FireObservation()
        seen.lane(allyClear = false, hullBlocked = false)
        seen.lane(allyClear = false, hullBlocked = false)
        assertEquals(2, seen.allyBlockedChecks)
        seen.lane(allyClear = false, hullBlocked = true)
        assertEquals(0, seen.allyBlockedChecks)
        assertEquals(1, seen.hullBlockedChecks)
        seen.lane(allyClear = true, hullBlocked = false)
        assertEquals(0, seen.hullBlockedChecks)
    }
}

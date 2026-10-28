package com.sbwnpc.squad.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MovementClaimsTest {
    private val events = mutableListOf<String>()
    private val claims = MovementClaims { events += it }
    private val cover = Any()
    private val evade = Any()
    private val medic = Any()

    @Test fun `nobody owns movement until a behaviour claims it`() {
        assertNull(claims.owner(0))
        assertFalse(claims.has(MovementClaim.COVER, 0))
    }

    @Test fun `the highest claim owns movement and the others are only suspended`() {
        claims.claim(medic, MovementClaim.HEAL, 0)
        claims.claim(cover, MovementClaim.COVER, 0)
        assertEquals(MovementClaim.COVER, claims.owner(0))
        assertTrue(claims.has(MovementClaim.HEAL, 0), "a suspended claim is still held")

        claims.claim(evade, MovementClaim.EVADE, 0)
        assertEquals(MovementClaim.EVADE, claims.owner(0))
        claims.release(evade, MovementClaim.EVADE, 0)
        assertEquals(MovementClaim.COVER, claims.owner(0), "the claim it outranked takes over again")
    }

    @Test fun `a walk to a Supply gives way to treatment, cover and evasion`() {
        claims.claim(Any(), MovementClaim.RESUPPLY, 0)
        assertEquals(MovementClaim.RESUPPLY, claims.owner(0))
        claims.claim(medic, MovementClaim.HEAL, 0)
        assertEquals(MovementClaim.HEAL, claims.owner(0))
        assertTrue(claims.has(MovementClaim.RESUPPLY, 0))
    }

    @Test fun `a lower claim does not take movement from a higher one`() {
        claims.claim(cover, MovementClaim.COVER, 0)
        claims.claim(medic, MovementClaim.HEAL, 0)
        assertEquals(MovementClaim.COVER, claims.owner(0))
        assertTrue(claims.ownedBy(cover, 0))
        assertFalse(claims.ownedBy(medic, 0))
    }

    @Test fun `claiming again is a refresh, not a second claim`() {
        claims.claim(medic, MovementClaim.HEAL, 0)
        claims.claim(medic, MovementClaim.HEAL, 1)
        assertEquals(1, events.count { it.startsWith("claimed") })
        claims.release(medic, MovementClaim.HEAL, 1)
        assertNull(claims.owner(1))
    }

    @Test fun `one behaviour stopping does not release another's claim of the same kind`() {
        val other = Any()
        claims.claim(cover, MovementClaim.COVER, 0)
        claims.claim(other, MovementClaim.COVER, 0)
        claims.release(cover, MovementClaim.COVER, 0)
        assertTrue(claims.has(MovementClaim.COVER, 0))
        claims.release(other, MovementClaim.COVER, 0)
        assertFalse(claims.has(MovementClaim.COVER, 0))
    }

    @Test fun `a claim with a lifetime ends by itself`() {
        claims.claim(evade, MovementClaim.EVADE, 0, lifetime = 10)
        assertTrue(claims.has(MovementClaim.EVADE, 9))
        assertFalse(claims.has(MovementClaim.EVADE, 10))
        assertNull(claims.owner(10))
    }

    @Test fun `taking over and giving back are reported with both sides`() {
        claims.claim(cover, MovementClaim.COVER, 0)
        claims.claim(evade, MovementClaim.EVADE, 0)
        claims.release(evade, MovementClaim.EVADE, 0)
        assertEquals(
            listOf(
                "claimed COVER owner=COVER",
                "claimed EVADE owner=EVADE preempted=COVER",
                "released EVADE owner=COVER resumed=COVER",
            ),
            events,
        )
    }

    @Test fun `releasing a claim that is not held reports nothing`() {
        claims.release(cover, MovementClaim.COVER, 0)
        assertTrue(events.isEmpty())
    }
}

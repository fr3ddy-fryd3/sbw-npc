package com.sbwnpc.squad.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NpcActivitiesTest {
    private fun only(activity: NpcActivity) = NpcActivities().with(activity, true)

    @Test fun `an activity is on until its behaviour clears it`() {
        var activities = NpcActivities()
        NpcActivity.entries.forEach { assertFalse(activities.has(it), "$it") }
        activities = activities.with(NpcActivity.DUG_IN, true).with(NpcActivity.CARRYING_MORTAR, true)
        assertTrue(activities.has(NpcActivity.DUG_IN))
        activities = activities.with(NpcActivity.DUG_IN, false)
        assertFalse(activities.has(NpcActivity.DUG_IN))
        assertTrue(activities.has(NpcActivity.CARRYING_MORTAR))
    }

    @Test fun `a mortar crewman serves, runs errands and carries the tube at once`() {
        var crew = NpcActivities().with(NpcActivity.MORTAR_CREW, true).with(NpcActivity.MORTAR_ERRAND, true).with(NpcActivity.CARRYING_MORTAR, true)
        assertTrue(crew.ownsHandsAndFeet())
        assertTrue(crew.movingMortar())
        crew = crew.with(NpcActivity.MORTAR_CREW, false)
        assertFalse(crew.ownsHandsAndFeet())
        assertTrue(crew.movingMortar())
    }

    // Every activity is placed in or out of each question here, so a new one cannot slip past a
    // check unnoticed.
    @Test fun `each question answers for exactly its activities`() {
        val placed = mapOf(
            NpcActivity.VEHICLE to (true to false),
            NpcActivity.DRONE to (true to false),
            NpcActivity.MORTAR_CREW to (true to false),
            NpcActivity.MORTAR_ERRAND to (false to true),
            NpcActivity.CARRYING_MORTAR to (false to true),
            NpcActivity.ANTI_DRONE to (true to false),
            NpcActivity.DUG_IN to (false to false),
            NpcActivity.SEAT to (false to false),
        )
        assertEquals(NpcActivity.entries.toSet(), placed.keys)
        placed.forEach { (activity, answers) ->
            assertEquals(answers.first, only(activity).ownsHandsAndFeet(), "ownsHandsAndFeet for $activity")
            assertEquals(answers.second, only(activity).movingMortar(), "movingMortar for $activity")
        }
        assertEquals(placed.filterValues { it.first }.keys, NpcActivities.ROLE_ACTIVITIES)
        assertEquals(placed.filterValues { it.second }.keys, NpcActivities.MORTAR_MOVE_ACTIVITIES)
    }
}

class ActivityClaimsTest {
    private val first = Any()
    private val second = Any()

    @Test fun `an activity ends only when every owner has released it`() {
        val claims = ActivityClaims()
        claims.claim(NpcActivity.VEHICLE, first, NpcActivities())
        claims.claim(NpcActivity.VEHICLE, second, NpcActivities())
        assertFalse(claims.release(NpcActivity.VEHICLE, first), "The second owner still holds it")
        assertTrue(claims.release(NpcActivity.VEHICLE, second))
    }

    @Test fun `claiming twice and releasing once ends it`() {
        val claims = ActivityClaims()
        claims.claim(NpcActivity.VEHICLE, first, NpcActivities())
        claims.claim(NpcActivity.VEHICLE, first, NpcActivities())
        assertEquals(1, claims.holders(NpcActivity.VEHICLE))
        assertTrue(claims.release(NpcActivity.VEHICLE, first))
    }

    @Test fun `owners are told apart by identity, not equality`() {
        val claims = ActivityClaims()
        claims.claim(NpcActivity.VEHICLE, String(charArrayOf('a')), NpcActivities())
        claims.claim(NpcActivity.VEHICLE, String(charArrayOf('a')), NpcActivities())
        assertEquals(2, claims.holders(NpcActivity.VEHICLE))
    }

    @Test fun `releasing what nobody claimed ends it`() {
        assertTrue(ActivityClaims().release(NpcActivity.VEHICLE, first))
    }

    @Test fun `a claim reports the held activities it cannot stand with`() {
        val claims = ActivityClaims()
        val held = NpcActivities().with(NpcActivity.DUG_IN, true).with(NpcActivity.CARRYING_MORTAR, true)
        assertEquals(setOf(NpcActivity.DUG_IN), claims.claim(NpcActivity.VEHICLE, first, held))
        assertEquals(emptySet<NpcActivity>(), claims.claim(NpcActivity.CARRYING_MORTAR, first, held))
    }

    @Test fun `a seat has one owner at a time`() {
        val claims = ActivityClaims()
        assertTrue(claims.claimAlone(NpcActivity.SEAT, first))
        assertTrue(claims.claimAlone(NpcActivity.SEAT, first), "Its owner claims it again")
        assertFalse(claims.freeFor(NpcActivity.SEAT, second))
        assertFalse(claims.claimAlone(NpcActivity.SEAT, second), "A second owner is refused")
        assertEquals(1, claims.holders(NpcActivity.SEAT))
        assertTrue(claims.release(NpcActivity.SEAT, first))
        assertTrue(claims.claimAlone(NpcActivity.SEAT, second), "Free again once released")
    }

    @Test fun `exclusion is symmetric`() {
        NpcActivity.entries.forEach { a ->
            NpcActivities.excluding(a).forEach { b -> assertTrue(a in NpcActivities.excluding(b), "$a and $b") }
        }
    }
}

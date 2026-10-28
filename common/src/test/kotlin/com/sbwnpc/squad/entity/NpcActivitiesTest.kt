package com.sbwnpc.squad.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NpcActivitiesTest {
    private val events = mutableListOf<String>()
    private val activities = NpcActivities { events += it }
    private val first = Any()
    private val second = Any()
    private val cover = Any()
    private val evade = Any()
    private val medic = Any()

    private fun only(kind: NpcActivity) = NpcActivities().also { it.claim(kind, Any(), 0) }

    @Test fun `nothing is held until a behaviour claims it`() {
        NpcActivity.entries.forEach { assertFalse(activities.has(it, 0), "$it") }
        assertNull(activities.holder(Cap.FEET, 0))
    }

    @Test fun `an activity ends only when every owner has released it`() {
        activities.claim(NpcActivity.VEHICLE, first, 0)
        activities.claim(NpcActivity.VEHICLE, second, 0)
        activities.release(NpcActivity.VEHICLE, first, 0)
        assertTrue(activities.has(NpcActivity.VEHICLE, 0), "the second owner still holds it")
        activities.release(NpcActivity.VEHICLE, second, 0)
        assertFalse(activities.has(NpcActivity.VEHICLE, 0))
    }

    @Test fun `claiming again is a refresh, not a second claim`() {
        activities.claim(NpcActivity.HEAL, medic, 0)
        activities.claim(NpcActivity.HEAL, medic, 1)
        assertEquals(1, events.count { it.startsWith("claimed") })
        activities.release(NpcActivity.HEAL, medic, 1)
        assertFalse(activities.has(NpcActivity.HEAL, 1))
    }

    @Test fun `owners are told apart by identity, not equality`() {
        val one = String(charArrayOf('a'))
        activities.claim(NpcActivity.VEHICLE, one, 0)
        activities.claim(NpcActivity.VEHICLE, String(charArrayOf('a')), 0)
        activities.release(NpcActivity.VEHICLE, one, 0)
        assertTrue(activities.has(NpcActivity.VEHICLE, 0), "an equal but different owner still holds it")
    }

    @Test fun `releasing what nobody claimed changes nothing and reports nothing`() {
        activities.release(NpcActivity.COVER, cover, 0)
        assertTrue(events.isEmpty())
    }

    @Test fun `a claim reports the held activities it cannot stand with`() {
        activities.claim(NpcActivity.DUG_IN, first, 0)
        activities.claim(NpcActivity.CARRYING_MORTAR, first, 0)
        assertEquals(setOf(NpcActivity.DUG_IN), activities.claim(NpcActivity.VEHICLE, first, 0))
        assertEquals(emptySet<NpcActivity>(), activities.claim(NpcActivity.CARRYING_MORTAR, first, 0))
    }

    @Test fun `a seat has one owner at a time`() {
        assertTrue(activities.claimAlone(NpcActivity.SEAT, first, 0))
        assertTrue(activities.claimAlone(NpcActivity.SEAT, first, 0), "its owner claims it again")
        assertFalse(activities.freeFor(NpcActivity.SEAT, second))
        assertFalse(activities.claimAlone(NpcActivity.SEAT, second, 0), "a second owner is refused")
        activities.release(NpcActivity.SEAT, first, 0)
        assertTrue(activities.claimAlone(NpcActivity.SEAT, second, 0), "free again once released")
    }

    @Test fun `exclusion is symmetric`() {
        NpcActivity.entries.forEach { a ->
            NpcActivities.excluding(a).forEach { b -> assertTrue(a in NpcActivities.excluding(b), "$a and $b") }
        }
    }

    @Test fun `a mortar crewman serves, runs errands and carries the tube at once`() {
        activities.claim(NpcActivity.MORTAR_CREW, first, 0)
        activities.claim(NpcActivity.MORTAR_ERRAND, first, 0)
        activities.claim(NpcActivity.CARRYING_MORTAR, second, 0)
        assertTrue(activities.ownsHandsAndFeet(0))
        assertTrue(activities.movingMortar(0))
        activities.release(NpcActivity.MORTAR_CREW, first, 0)
        assertFalse(activities.ownsHandsAndFeet(0))
        assertTrue(activities.movingMortar(0))
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
            NpcActivity.RESUPPLY to (false to false),
            NpcActivity.HEAL to (false to false),
            NpcActivity.COVER to (false to false),
            NpcActivity.EVADE to (false to false),
        )
        assertEquals(NpcActivity.entries.toSet(), placed.keys)
        placed.forEach { (activity, answers) ->
            assertEquals(answers.first, only(activity).ownsHandsAndFeet(0), "ownsHandsAndFeet for $activity")
            assertEquals(answers.second, only(activity).movingMortar(0), "movingMortar for $activity")
        }
        assertEquals(placed.filterValues { it.first }.keys, NpcActivities.ROLE_ACTIVITIES)
        assertEquals(placed.filterValues { it.second }.keys, NpcActivities.MORTAR_MOVE_ACTIVITIES)
    }

    @Test fun `the highest claim holds the feet and the others are only suspended`() {
        activities.claim(NpcActivity.HEAL, medic, 0)
        activities.claim(NpcActivity.COVER, cover, 0)
        assertEquals(NpcActivity.COVER, activities.holder(Cap.FEET, 0))
        assertTrue(activities.has(NpcActivity.HEAL, 0), "a suspended claim is still held")

        activities.claim(NpcActivity.EVADE, evade, 0)
        assertEquals(NpcActivity.EVADE, activities.holder(Cap.FEET, 0))
        activities.release(NpcActivity.EVADE, evade, 0)
        assertEquals(NpcActivity.COVER, activities.holder(Cap.FEET, 0), "the claim it outranked takes over again")
    }

    @Test fun `a lower claim does not take the feet from a higher one`() {
        activities.claim(NpcActivity.COVER, cover, 0)
        activities.claim(NpcActivity.HEAL, medic, 0)
        assertEquals(NpcActivity.COVER, activities.holder(Cap.FEET, 0))
    }

    @Test fun `a role outranks every movement claim`() {
        for (movement in listOf(NpcActivity.RESUPPLY, NpcActivity.HEAL, NpcActivity.COVER, NpcActivity.EVADE)) {
            val held = NpcActivities()
            held.claim(movement, cover, 0)
            held.claim(NpcActivity.VEHICLE, first, 0)
            assertEquals(NpcActivity.VEHICLE, held.holder(Cap.FEET, 0), "$movement")
            assertFalse(held.mayFor(movement, Cap.FEET, 0), "$movement")
        }
    }

    @Test fun `a walk to a Supply holds the feet but never the trigger`() {
        activities.claim(NpcActivity.RESUPPLY, first, 0)
        assertEquals(NpcActivity.RESUPPLY, activities.holder(Cap.FEET, 0))
        assertNull(activities.holder(Cap.FIRE, 0))
        assertTrue(activities.may(Cap.FIRE, 0))
        assertFalse(activities.may(Cap.FEET, 0))
    }

    @Test fun `a dug-in man keeps his feet in the hole and his rifle free`() {
        activities.claim(NpcActivity.DUG_IN, first, 0)
        assertEquals(NpcActivity.DUG_IN, activities.holder(Cap.FEET, 0))
        assertNull(activities.holder(Cap.FIRE, 0))
    }

    @Test fun `treatment, cover and evasion hold feet and trigger`() {
        for (kind in listOf(NpcActivity.HEAL, NpcActivity.COVER, NpcActivity.EVADE)) {
            val fresh = NpcActivities()
            fresh.claim(kind, Any(), 0)
            assertEquals(kind, fresh.holder(Cap.FEET, 0))
            assertEquals(kind, fresh.holder(Cap.FIRE, 0))
        }
    }

    @Test fun `a kind may use a capability unless a higher kind holds it`() {
        activities.claim(NpcActivity.COVER, cover, 0)
        assertFalse(activities.mayFor(NpcActivity.HEAL, Cap.FEET, 0))
        assertFalse(activities.mayFor(NpcActivity.RESUPPLY, Cap.FIRE, 0), "cover holds the trigger too")
        assertTrue(activities.mayFor(NpcActivity.EVADE, Cap.FEET, 0))
        assertTrue(activities.mayFor(NpcActivity.COVER, Cap.FEET, 0), "a kind is not blocked by itself")
    }

    @Test fun `a lower kind never blocks a higher one`() {
        activities.claim(NpcActivity.RESUPPLY, first, 0)
        assertTrue(activities.mayFor(NpcActivity.HEAL, Cap.FEET, 0))
        assertTrue(activities.mayFor(NpcActivity.HEAL, Cap.FIRE, 0))
    }

    @Test fun `a claim with a lifetime ends by itself`() {
        activities.claim(NpcActivity.EVADE, evade, 0, lifetime = 10)
        assertTrue(activities.has(NpcActivity.EVADE, 9))
        assertEquals(NpcActivity.EVADE, activities.holder(Cap.FIRE, 9))
        assertFalse(activities.has(NpcActivity.EVADE, 10))
        assertNull(activities.holder(Cap.FIRE, 10))
        assertTrue(activities.may(Cap.FEET, 10))
    }

    @Test fun `taking over and giving back are reported with both sides`() {
        activities.claim(NpcActivity.COVER, cover, 0)
        activities.claim(NpcActivity.EVADE, evade, 0)
        activities.release(NpcActivity.EVADE, evade, 0)
        assertEquals(
            listOf(
                "claimed COVER holder=COVER",
                "claimed EVADE holder=EVADE preempted=COVER",
                "released EVADE holder=COVER resumed=COVER",
            ),
            events,
        )
    }
}

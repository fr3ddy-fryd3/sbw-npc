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
        activities = activities.with(NpcActivity.DUG_IN, true).with(NpcActivity.RESUPPLYING, true)
        assertTrue(activities.has(NpcActivity.DUG_IN))
        activities = activities.with(NpcActivity.DUG_IN, false)
        assertFalse(activities.has(NpcActivity.DUG_IN))
        assertTrue(activities.has(NpcActivity.RESUPPLYING))
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
            NpcActivity.RESUPPLYING to (false to false),
            NpcActivity.DUG_IN to (false to false),
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

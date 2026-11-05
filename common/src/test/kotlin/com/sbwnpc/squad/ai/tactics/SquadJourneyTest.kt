package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SquadJourneyTest {
    private val mob = JourneyContact(enemy = false, mob = true)
    private val enemy = JourneyContact(enemy = true, mob = false)

    @Test fun `a distant squad sets off unless something is close, and a near one never travels`() {
        assertTrue(SquadJourney.travels(distant = true, JourneyContact.NONE, travelling = false))
        assertFalse(SquadJourney.travels(distant = true, mob, travelling = false))
        assertFalse(SquadJourney.travels(distant = true, enemy, travelling = false))
        assertFalse(SquadJourney.travels(distant = false, JourneyContact.NONE, travelling = true))
    }

    @Test fun `once under way a mob closing in does not stop the trip, an enemy squad or player does`() {
        assertTrue(SquadJourney.travels(distant = true, mob, travelling = true))
        assertFalse(SquadJourney.travels(distant = true, enemy, travelling = true))
    }
}

package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SquadJourneyTest {
    private val mob = JourneyContact(enemy = false, mob = true)
    private val enemy = JourneyContact(enemy = true, mob = false)

    @Test fun `a distant squad sets off unless something is close, and a near one never travels`() {
        assertTrue(SquadJourney.travels(distant = true, JourneyContact.NONE, travelling = false, fought = false))
        assertFalse(SquadJourney.travels(distant = true, mob, travelling = false, fought = false))
        assertFalse(SquadJourney.travels(distant = true, enemy, travelling = false, fought = false))
        assertFalse(SquadJourney.travels(distant = false, JourneyContact.NONE, travelling = true, fought = false))
    }

    @Test fun `once under way a mob closing in does not stop the trip, an enemy squad or player does`() {
        assertTrue(SquadJourney.travels(distant = true, mob, travelling = true, fought = false))
        assertFalse(SquadJourney.travels(distant = true, enemy, travelling = true, fought = false))
    }

    @Test fun `a squad that fought its attack's defence never travels again, contact or not`() {
        assertFalse(SquadJourney.travels(distant = true, JourneyContact.NONE, travelling = false, fought = true))
        assertFalse(SquadJourney.travels(distant = true, JourneyContact.NONE, travelling = true, fought = true))
    }
}

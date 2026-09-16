package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.SquadFaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TeamAwarenessTest {
    private val faction = SquadFaction.PIG
    private val enemy = UUID.randomUUID()

    @BeforeEach fun reset() = TeamAwareness.clearAll()
    @AfterEach fun cleanup() = TeamAwareness.clearAll()

    @Test
    fun `contact is relayed only after the delay and while fresh`() {
        TeamAwareness.report(faction, enemy, 10_000)
        assertTrue(TeamAwareness.relayedContacts(faction, 10_000 + TeamAwareness.ALERT_DELAY_TICKS - 1).isEmpty())
        TeamAwareness.report(faction, enemy, 10_000 + TeamAwareness.ALERT_DELAY_TICKS)
        assertEquals(listOf(enemy), TeamAwareness.relayedContacts(faction, 10_000 + TeamAwareness.ALERT_DELAY_TICKS))
    }

    @Test
    fun `a new world clock cannot reuse an old contact`() {
        TeamAwareness.report(faction, enemy, 10_000)
        TeamAwareness.clearAll()
        TeamAwareness.report(faction, enemy, 100)
        assertTrue(TeamAwareness.relayedContacts(faction, 100).isEmpty())
    }
}

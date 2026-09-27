package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TeamAwarenessTest {
    private val faction = SquadFaction.PIG
    private val enemy = UUID.randomUUID()
    private val here = Vec3(1.0, 64.0, 2.0)

    @BeforeEach fun reset() = TeamAwareness.clearAll()
    @AfterEach fun cleanup() = TeamAwareness.clearAll()

    @Test
    fun `contact is relayed only after the delay and while fresh`() {
        TeamAwareness.report(faction, enemy, here, 10_000, "test")
        assertTrue(TeamAwareness.relayedContacts(faction, 10_000 + TeamAwareness.ALERT_DELAY_TICKS - 1).isEmpty())
        TeamAwareness.report(faction, enemy, here, 10_000 + TeamAwareness.ALERT_DELAY_TICKS, "test")
        assertEquals(listOf(enemy), TeamAwareness.relayedContacts(faction, 10_000 + TeamAwareness.ALERT_DELAY_TICKS))
    }

    @Test
    fun `a new world clock cannot reuse an old contact`() {
        TeamAwareness.report(faction, enemy, here, 10_000, "test")
        TeamAwareness.clearAll()
        TeamAwareness.report(faction, enemy, here, 100, "test")
        assertTrue(TeamAwareness.relayedContacts(faction, 100).isEmpty())
    }

    @Test
    fun `an unseen killer alerts infantry but is no fire-support target`() {
        TeamAwareness.reportUnseen(faction, enemy, 10_000)
        val later = 10_000 + TeamAwareness.ALERT_DELAY_TICKS
        TeamAwareness.reportUnseen(faction, enemy, later)
        assertEquals(listOf(enemy), TeamAwareness.relayedContacts(faction, later))
        assertTrue(TeamAwareness.sightings(faction, later).isEmpty())
    }

    @Test
    fun `fire support gets the spot the enemy was last seen at`() {
        TeamAwareness.report(faction, enemy, here, 10_000, "test")
        val there = Vec3(50.0, 64.0, 50.0)
        val later = 10_000 + TeamAwareness.ALERT_DELAY_TICKS
        TeamAwareness.report(faction, enemy, there, later, "test")
        TeamAwareness.reportUnseen(faction, enemy, later + 5)
        val sighting = TeamAwareness.sightings(faction, later + 5).single()
        assertEquals(there, sighting.pos)
        assertEquals(later, sighting.tick)
    }
}

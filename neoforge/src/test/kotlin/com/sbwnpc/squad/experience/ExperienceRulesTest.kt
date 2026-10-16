package com.sbwnpc.squad.experience

import com.sbwnpc.squad.npc.NpcRank
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ExperienceRulesTest {
    @Test
    fun `target healing and repeated death cannot refill the lifetime reward budget`() {
        val id = UUID.randomUUID()
        val tag = CompoundTag()
        var total = ExperienceLedger(tag).damage(0.75, 100.0, mapOf(id to 1.0)).getValue(id)
        // Persist between hits and pretend the enemy was fully healed.
        val resumed = ExperienceLedger(tag.copy())
        total += resumed.damage(1.0, 100.0, mapOf(id to 1.0)).getValue(id)
        total += resumed.finish(100.0).getValue(id)
        assertEquals(100.0, total, 1e-8)
        assertTrue(resumed.damage(1.0, 100.0, mapOf(id to 1.0)).isEmpty())
        assertTrue(resumed.finish(100.0).isEmpty())
    }

    @Test
    fun `assist reward follows actual damage including crew shares instead of the last hit`() {
        val first = UUID.randomUUID()
        val shooter = UUID.randomUUID()
        val driver = UUID.randomUUID()
        val ledger = ExperienceLedger(CompoundTag())
        assertEquals(52.5, ledger.damage(0.75, 100.0, mapOf(first to 1.0))[first]!!, 1e-8)
        val hit = ledger.damage(0.25, 100.0, mapOf(shooter to 0.75, driver to 0.25))
        assertEquals(13.125, hit[shooter]!!, 1e-8)
        assertEquals(4.375, hit[driver]!!, 1e-8)
        val death = ledger.finish(100.0)
        assertEquals(22.5, death[first]!!, 1e-8)
        assertEquals(5.625, death[shooter]!!, 1e-8)
        assertEquals(1.875, death[driver]!!, 1e-8)
    }

    @Test
    fun `small hits conserve fractional rewards and rank thresholds are cumulative`() {
        val id = UUID.randomUUID()
        val ledger = ExperienceLedger(CompoundTag())
        val damage = (1..1000).sumOf { ledger.damage(0.001, 30.0, mapOf(id to 1.0))[id] ?: 0.0 }
        assertEquals(30.0, damage + ledger.finish(30.0).getValue(id), 1e-8)
        for (rank in NpcRank.entries) {
            assertEquals(rank, ExperienceRules.rank(ExperienceRules.threshold(rank)))
            if (rank != NpcRank.RECRUIT) assertEquals(NpcRank.entries[rank.ordinal - 1], ExperienceRules.rank(ExperienceRules.threshold(rank) - 0.001))
        }
    }
}

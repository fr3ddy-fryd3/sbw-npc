package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** A mortar crew's target choice keeps today's priority and changes only with the target (#258). */
class FireSupportTargetsTest {
    private val enemy = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val all = FireOptions(barrage = true, preparation = true, commanded = "focus", ownSighting = enemy, factionSighting = other, orderKey = "order")

    @Test fun `the priority is barrage, preparation, commanded, own sighting, faction sighting`() {
        assertEquals(FireSource.ORDER_BARRAGE, FireSupportTargets.choose(all, null)?.source)
        assertEquals(FireSource.ATTACK_PREPARATION, FireSupportTargets.choose(all.copy(barrage = false), null)?.source)
        assertEquals(FireSource.COMMANDED, FireSupportTargets.choose(all.copy(barrage = false, preparation = false), null)?.source)
        assertEquals(FireSource.OWN_SIGHTING, FireSupportTargets.choose(FireOptions(ownSighting = enemy, factionSighting = other), null)?.source)
        assertEquals(FireSource.FACTION_SIGHTING, FireSupportTargets.choose(FireOptions(factionSighting = other), null)?.source)
        assertNull(FireSupportTargets.choose(FireOptions(), null))
    }

    @Test fun `the same enemy known another way keeps the target, another enemy changes it`() {
        val own = FireSupportTargets.choose(FireOptions(ownSighting = enemy), null)
        assertSame(own, FireSupportTargets.choose(FireOptions(factionSighting = enemy, sighted = setOf(enemy.toString())), own))
        assertEquals(FireTarget(FireSource.FACTION_SIGHTING, other.toString()), FireSupportTargets.choose(FireOptions(factionSighting = other), own))
    }

    @Test fun `a target still in view is kept when another enemy is reported fresher, and dropped once out of view`() {
        val first = FireSupportTargets.choose(FireOptions(factionSighting = enemy, sighted = setOf(enemy.toString())), null)
        val both = setOf(enemy.toString(), other.toString())
        assertSame(first, FireSupportTargets.choose(FireOptions(factionSighting = other, sighted = both), first))
        assertEquals(other.toString(), FireSupportTargets.choose(FireOptions(factionSighting = other, sighted = setOf(other.toString())), first)?.key)
    }

    @Test fun `a new order is a new target, the same order is not`() {
        val first = FireSupportTargets.choose(FireOptions(barrage = true, orderKey = "squad:1"), null)
        assertEquals(first, FireSupportTargets.choose(FireOptions(barrage = true, orderKey = "squad:1"), first))
        assertNotEquals(first, FireSupportTargets.choose(FireOptions(barrage = true, orderKey = "squad:2"), first))
    }

    @Test fun `an own sighting holds for two scans`() {
        assertEquals(2L * FireSupportTargets.SCAN_INTERVAL_TICKS, FireSupportTargets.OWN_SIGHTING_HOLD_TICKS)
    }
}

package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.tactics.TacticalTargetSelection.Opportunity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalTargetSelectionTest {
    @Test fun `a shootable current contact is retained without redistributing fire`() {
        val assessed = mutableListOf<Int>()
        val selected = TacticalTargetSelection.choose(listOf(1, 2, 3), 3, 0, {
            assessed += it
            Opportunity.SHOOTABLE
        }, { error("retained fire must not be reallocated") })
        assertEquals(3, selected)
        assertEquals(listOf(3), assessed)
    }

    @Test fun `a clear firing lane wins over a visible enemy behind glass or an ally`() {
        val selected = TacticalTargetSelection.choose(listOf(1, 2, 3), 1, 0, {
            when (it) {
                1 -> Opportunity.BLOCKED
                2 -> Opportunity.VISIBLE
                else -> Opportunity.SHOOTABLE
            }
        }, { it.first() })
        assertEquals(3, selected)
    }

    @Test fun `bounded scans eventually test contacts beyond the first four`() {
        val contacts = (1..10).toList()
        val assessed = mutableListOf<Int>()
        fun scan(cursor: Int) =
            TacticalTargetSelection.choose(contacts, 1, cursor, {
                assessed += it
                if (it == 9) Opportunity.SHOOTABLE else Opportunity.BLOCKED
            }, { it.first() })
        assertEquals(1, scan(0))
        assertEquals(1, scan(4))
        assertEquals(9, scan(8))
        assertTrue(contacts.all { it in assessed })
    }

    @Test fun `blocked contacts retain a known target without inventing an undiscovered one`() {
        assertEquals(2, TacticalTargetSelection.choose(listOf(1, 2), 2, 0, { Opportunity.BLOCKED }, { it.first() }))
        assertEquals(1, TacticalTargetSelection.choose(listOf(1, 2), 99, 0, { Opportunity.BLOCKED }, { it.first() }))
        assertNull(TacticalTargetSelection.choose(emptyList<Int>(), 99, 0, { error("no contact") }, { error("no contact") }))
    }

    @Test fun `periodic review reallocates a shootable target using bounded probes and includes the current target`() {
        val assessed = mutableListOf<Int>()
        var offered = emptyList<Int>()
        val selected = TacticalTargetSelection.choose((1..12).toList(), 12, 0, {
            assessed += it
            Opportunity.SHOOTABLE
        }, {
            offered = it
            it.minOrNull()
        }, reconsiderCurrent = true)
        assertEquals(1, selected)
        assertEquals(setOf(1, 2, 3, 4, 12), assessed.toSet())
        assertEquals(5, assessed.size, "Each opportunity is checked at most once")
        assertTrue(12 in offered, "The good current target remains eligible outside the probe window")
    }

    @Test fun `periodic review does not replace usable fire with a blocked or merely visible target`() {
        val chosen = TacticalTargetSelection.choose((1..12).toList(), 12, 0, {
            when (it) {
                12 -> Opportunity.SHOOTABLE
                1 -> Opportunity.VISIBLE
                else -> Opportunity.BLOCKED
            }
        }, { it.first() }, reconsiderCurrent = true)
        assertEquals(12, chosen)
    }
}

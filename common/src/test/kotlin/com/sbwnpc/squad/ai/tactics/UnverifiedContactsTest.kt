package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class UnverifiedContactsTest {
    @Test fun `a position away from the objective stops holding it open after 30 s unseen`() {
        // In-game 16.10: one position 89 blocks away, no route to it, kept the attack open 6 min.
        assertFalse(UnverifiedContacts.expired(89.0, 600))
        assertTrue(UnverifiedContacts.expired(89.0, 601))
    }

    @Test fun `a position at the objective is checked for longer`() {
        assertFalse(UnverifiedContacts.expired(20.0, 601))
        assertTrue(UnverifiedContacts.expired(20.0, 2401))
    }

    @Test fun `scouts check the nearest remembered position within reach of the point`() {
        val point = Point3(193.0, 102.0, -173.0)
        val far = Point3(191.0, 88.0, -84.0)
        val near = Point3(200.0, 100.0, -140.0)
        assertEquals(near, UnverifiedContacts.scoutTarget(point, listOf(far, near)))
        // In-game 16.10: the only remembered position was 89 blocks out; no scout goes there.
        assertNull(UnverifiedContacts.scoutTarget(point, listOf(far)))
    }

    @Test fun `nothing is inspected in a fight or on a reinforcement's march`() {
        val anchor = Point3(0.0, 64.0, 0.0)
        val remembered = listOf(Point3(10.0, 64.0, 0.0))
        val attack = Point3(50.0, 64.0, 0.0)
        val unasked = { error("the anchor is not needed in a fight or on the march") }
        assertNull(UnverifiedContacts.inspectTarget(inContact = true, marching = false, unasked, remembered, attack))
        assertNull(UnverifiedContacts.inspectTarget(inContact = false, marching = true, unasked, remembered, attack))
        assertEquals(remembered.first(), UnverifiedContacts.inspectTarget(inContact = false, marching = false, { anchor }, remembered, attack))
    }

    @Test fun `with no remembered position in reach the scouts check the attacked objective`() {
        val anchor = Point3(0.0, 64.0, 0.0)
        val far = listOf(Point3(100.0, 64.0, 0.0))
        val attack = Point3(50.0, 64.0, 0.0)
        assertEquals(attack, UnverifiedContacts.inspectTarget(inContact = false, marching = false, { anchor }, far, attack))
        assertNull(UnverifiedContacts.inspectTarget(inContact = false, marching = false, { anchor }, far, null))
    }

    @Test fun `remembered positions are measured from the area, then the order's objective, then the men`() {
        val area = Point3(1.0, 64.0, 1.0)
        val order = Point3(2.0, 64.0, 2.0)
        val centre = Point3(3.0, 64.0, 3.0)
        assertEquals(area, UnverifiedContacts.inspectAnchor(area, order, centre))
        assertEquals(order, UnverifiedContacts.inspectAnchor(null, order, centre))
        assertEquals(centre, UnverifiedContacts.inspectAnchor(null, null, centre))
    }

    @Test fun `scouts already out keep the job, then the nearest go`() {
        val inspect = Point3(0.0, 64.0, 0.0)
        val (near, mid, far) = List(3) { UUID.randomUUID() }
        val candidates = listOf(far to Point3(30.0, 64.0, 0.0), mid to Point3(20.0, 64.0, 0.0), near to Point3(10.0, 64.0, 0.0))
        assertEquals(setOf(near, mid), UnverifiedContacts.scouts(candidates, emptySet(), inspect))
        assertEquals(setOf(far, near), UnverifiedContacts.scouts(candidates, setOf(far), inspect))
        assertEquals(emptySet<UUID>(), UnverifiedContacts.scouts(candidates, emptySet(), null))
    }

    @Test fun `scouts at the same distance go in the order they were listed, height included`() {
        val inspect = Point3(0.0, 64.0, 0.0)
        val (a, b, c) = List(3) { UUID.randomUUID() }
        val candidates = listOf(a to Point3(10.0, 64.0, 0.0), b to Point3(0.0, 64.0, 10.0), c to Point3(0.0, 74.0, 0.0))
        assertEquals(setOf(a, b), UnverifiedContacts.scouts(candidates, emptySet(), inspect))
        // Ten blocks up counts as far as ten across.
        assertEquals(setOf(c, a), UnverifiedContacts.scouts(listOf(c to Point3(0.0, 74.0, 0.0), a to Point3(10.0, 64.0, 0.0), b to Point3(0.0, 64.0, 10.0)), emptySet(), inspect))
    }
}

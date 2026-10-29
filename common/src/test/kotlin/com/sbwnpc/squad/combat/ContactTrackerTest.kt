package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContactTrackerTest {
    private val enemy = Vec3(10.0, 64.0, 5.0)

    @Test fun `contact ends thirty seconds after the last target at the last known position`() {
        val tracker = ContactTracker()
        tracker.update(Vec3.ZERO, 0)
        tracker.update(enemy, 100)
        assertNull(tracker.update(null, 100 + ContactTracker.CONTACT_END_TICKS - 1))
        assertEquals(enemy, tracker.update(null, 100 + ContactTracker.CONTACT_END_TICKS))
    }

    @Test fun `a new target restarts the count`() {
        val tracker = ContactTracker()
        tracker.update(enemy, 0)
        tracker.update(Vec3.ZERO, 500)
        assertNull(tracker.update(null, 600))
        assertEquals(Vec3.ZERO, tracker.update(null, 1100))
    }

    @Test fun `the end is reported once and never without a contact`() {
        val tracker = ContactTracker()
        assertNull(tracker.update(null, 5000))
        tracker.update(enemy, 5000)
        assertEquals(enemy, tracker.update(null, 5600))
        assertNull(tracker.update(null, 5601))
    }
}

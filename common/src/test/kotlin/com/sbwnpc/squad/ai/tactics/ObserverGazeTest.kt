package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.tactics.ObserverGaze.Cues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ObserverGazeTest {
    private val post = Point3(0.0, 70.0, 0.0)

    private fun at(x: Double, z: Double) = Point3(x, 70.0, z)

    @Test fun `a known contact comes first`() {
        val (dx, dz) = ObserverGaze.direction(post, Cues(at(10.0, 0.0), at(0.0, 10.0), at(-10.0, 0.0), true, at(0.0, -10.0)))!!
        assertEquals(1.0, dx, 1e-9)
        assertEquals(0.0, dz, 1e-9)
    }

    @Test fun `without a contact the direction fire came from is next, then the objective`() {
        assertEquals(0.0 to 1.0, ObserverGaze.direction(post, Cues(null, at(0.0, 10.0), at(-10.0, 0.0), true, null)))
        assertEquals(-1.0 to 0.0, ObserverGaze.direction(post, Cues(null, null, at(-10.0, 0.0), true, at(0.0, -10.0))))
    }

    @Test fun `a defence faces outward from the objective, an attack towards it`() {
        assertEquals(1.0 to 0.0, ObserverGaze.direction(at(10.0, 0.0), Cues(null, null, post, false, null)))
        assertEquals(-1.0 to 0.0, ObserverGaze.direction(at(10.0, 0.0), Cues(null, null, post, true, null)))
    }

    @Test fun `with nothing else he faces away from the squad`() {
        assertEquals(0.0 to 1.0, ObserverGaze.direction(post, Cues(null, null, null, false, at(0.0, -10.0))))
    }

    @Test fun `a point on top of him says nothing and the next one is used`() {
        assertEquals(1.0 to 0.0, ObserverGaze.direction(post, Cues(at(1.0, 1.0), null, at(10.0, 0.0), true, null)))
        assertNull(ObserverGaze.direction(post, Cues(at(1.0, 1.0), null, null, true, at(0.0, 2.0))))
    }

    @Test fun `no input gives no direction`() {
        assertNull(ObserverGaze.direction(post, Cues(null, null, null, false, null)))
    }
}

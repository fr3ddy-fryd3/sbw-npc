package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeCloudsTest {
    private val from = Vec3(0.0, 0.0, 0.0)
    private val to = Vec3(10.0, 0.0, 0.0)

    @Test fun `a point beside the middle of the line is its perpendicular distance away`() {
        assertEquals(2.0, SmokeClouds.distanceToSegment(from, to, Vec3(5.0, 2.0, 0.0)), 1e-9)
    }

    @Test fun `a point past an end is measured to that end, not to the line beyond it`() {
        assertEquals(3.0, SmokeClouds.distanceToSegment(from, to, Vec3(13.0, 0.0, 0.0)), 1e-9)
        assertEquals(5.0, SmokeClouds.distanceToSegment(from, to, Vec3(-3.0, 4.0, 0.0)), 1e-9)
    }

    @Test fun `a zero length line is a point`() {
        assertEquals(5.0, SmokeClouds.distanceToSegment(from, from, Vec3(3.0, 4.0, 0.0)), 1e-9)
    }
}

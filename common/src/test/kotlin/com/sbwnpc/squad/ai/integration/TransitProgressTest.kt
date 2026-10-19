package com.sbwnpc.squad.ai.integration

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransitProgressTest {
    private val from = Vec3(90.5, 63.0, 12.5)
    private val anchor = Vec3(40.5, 63.0, 30.5)

    @Test fun `a man's own spot is no MOVE post even when his route leg lies beside him`() {
        val leg = from.add(2.0, 0.0, 1.0)
        assertFalse(NativePositionAction.advances(from, anchor, leg, from))
    }

    @Test fun `a post nearer the anchor is on the way`() {
        val point = from.add(anchor.subtract(from).normalize().scale(8.0))
        assertTrue(NativePositionAction.advances(from, anchor, from, point))
    }

    @Test fun `a post near a route leg that goes round may lead away from the anchor`() {
        // The leg turns away from the anchor to get round an obstacle; a post just short of it
        // is farther from the anchor than the man but still on his way.
        val leg = from.add(0.0, 0.0, -20.0)
        val point = leg.add(0.0, 0.0, 3.0)
        assertTrue(point.distanceTo(anchor) > from.distanceTo(anchor))
        assertTrue(NativePositionAction.advances(from, anchor, leg, point))
    }

    @Test fun `a post beside the leg but no nearer to it than the man is rejected`() {
        val leg = from.add(0.0, 0.0, -4.0)
        val point = from.add(0.0, 0.0, -8.0)
        assertFalse(point.distanceTo(anchor) < from.distanceTo(anchor))
        assertFalse(NativePositionAction.advances(from, anchor, leg, point))
    }
}

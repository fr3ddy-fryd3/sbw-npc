package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalSearchBatchTest {
    @Test fun `a covered batch can be routed without finishing the disk but height and exposed searches wait`() {
        val origin = Vec3(0.5, 64.0, 0.5)
        val search = TacticalPositionSearch(origin, TacticalFirePosts.probes(origin))
        search.probeIndex = 24
        search.scored[origin] = TacticalPositionScore(true, 1, 0.0)
        assertFalse(search.readyBatch(overwatch = false))
        search.scored[origin.add(2.0, 0.0, 0.0)] = TacticalPositionScore(false, 0, 2.0)
        assertTrue(search.readyBatch(overwatch = false))
        assertFalse(search.readyBatch(overwatch = true))
        search.clearCandidates()
        assertEquals(24, search.probeIndex, "An unreachable batch must continue at the next uninspected candidate")
        assertFalse(search.readyBatch(overwatch = false))
    }
}

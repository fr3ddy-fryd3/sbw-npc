package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalAimTest {
    @Test fun `search restart predicate preserves the cursor and detects changed aim or origin`() {
        val origin = Vec3(0.5, 64.0, 0.5)
        val target = TacticalAim(UUID(0, 1), origin.add(80.0, 0.0, 0.0), origin.add(80.0, 1.62, 0.0))
        val search = TacticalPositionSearch(origin, TacticalFirePosts.probes(origin, recovery = true), target)
        search.probeIndex = 24
        assertFalse(search.needsRestart(origin, target))
        assertFalse(search.needsRestart(origin, target.copy(eye = target.eye.add(0.5, 0.0, 0.0))))
        assertEquals(24, search.probeIndex)
        // 80 blocks away the aim may move about 11 blocks: a switch to an enemy beside the old one keeps the scan.
        assertFalse(search.needsRestart(origin, target.copy(target = UUID(0, 2), eye = target.eye.add(0.0, 0.0, 3.0))))
        assertFalse(search.needsRestart(origin, target.copy(eye = target.eye.add(3.0, 0.0, 0.0))))
        assertTrue(search.needsRestart(origin, target.copy(target = UUID(0, 2), eye = target.eye.add(0.0, 0.0, 30.0))))
        assertEquals("target", search.restartReason(origin, target.copy(target = UUID(0, 2), eye = target.eye.add(0.0, 0.0, 30.0))))
        assertTrue(search.needsRestart(origin.add(5.0, 0.0, 0.0), target))
        assertTrue(search.needsRestart(origin, null))
    }

    @Test fun `the aim tolerance is two blocks up close and twelve at most far away`() {
        val origin = Vec3(0.5, 64.0, 0.5)
        val near = TacticalAim(UUID(0, 1), origin.add(10.0, 0.0, 0.0), origin.add(10.0, 1.62, 0.0))
        val nearSearch = TacticalPositionSearch(origin, emptyList(), near)
        assertFalse(nearSearch.needsRestart(origin, near.copy(eye = near.eye.add(0.0, 0.0, 1.5))))
        assertEquals("aim_moved", nearSearch.restartReason(origin, near.copy(eye = near.eye.add(0.0, 0.0, 2.5))))
        val far = TacticalAim(UUID(0, 1), origin.add(300.0, 0.0, 0.0), origin.add(300.0, 1.62, 0.0))
        val farSearch = TacticalPositionSearch(origin, emptyList(), far)
        assertFalse(farSearch.needsRestart(origin, far.copy(eye = far.eye.add(0.0, 0.0, 11.0))))
        assertTrue(farSearch.needsRestart(origin, far.copy(eye = far.eye.add(0.0, 0.0, 13.0))))
    }

    @Test fun `a sector aim that turns into a target nearby keeps the scan, one far off restarts it`() {
        val origin = Vec3(0.5, 64.0, 0.5)
        val sector = TacticalAim.choose(null, origin.add(80.0, 0.0, 0.0))!!
        val search = TacticalPositionSearch(origin, emptyList(), sector)
        assertFalse(search.needsRestart(origin, TacticalAim(UUID(0, 1), sector.position.add(0.0, 0.0, 4.0), sector.eye.add(0.0, 0.0, 4.0))))
        assertTrue(search.needsRestart(origin, TacticalAim(UUID(0, 1), sector.position.add(0.0, 0.0, 40.0), sector.eye.add(0.0, 0.0, 40.0))))
    }

    @Test fun `aim selection prefers the supplied gun target over the sector center`() {
        val sector = Vec3(80.0, 64.0, 0.0)
        val target = TacticalAim(UUID(0, 1), Vec3(80.0, 68.0, 30.0), Vec3(80.0, 69.62, 30.0))
        assertSame(target, TacticalAim.choose(target, sector))
        assertSame(target, TacticalAim.choose(target, null))
        assertEquals(sector.add(0.0, 1.5, 0.0), TacticalAim.choose(null, sector)?.eye)
        assertNull(TacticalAim.choose(null, null))
    }
}

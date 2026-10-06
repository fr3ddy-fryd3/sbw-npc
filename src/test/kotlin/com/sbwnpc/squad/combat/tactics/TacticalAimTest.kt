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
        assertTrue(search.needsRestart(origin, target.copy(target = UUID(0, 2))))
        assertTrue(search.needsRestart(origin, target.copy(eye = target.eye.add(3.0, 0.0, 0.0))))
        assertTrue(search.needsRestart(origin.add(5.0, 0.0, 0.0), target))
        assertTrue(search.needsRestart(origin, null))
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

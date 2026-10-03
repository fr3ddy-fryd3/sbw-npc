package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IncomingFireTest {
    @Test fun `projectile trajectory wins over exact damage source coordinates`() {
        assertEquals(Vec3(-1.0, -0.0, -0.0), IncomingFire.direction(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), Vec3(0.0, 0.0, 20.0)))
        assertNull(IncomingFire.direction(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO))
    }
    @Test fun `sustained hits do not replenish the two burst budget`() {
        val fire = IncomingFire()
        fire.record(Vec3.ZERO, Vec3(-1.0, 0.0, 0.0), null, 100)
        fire.beginReply(120)
        for (tick in listOf(120L, 122L, 124L, 134L, 136L, 138L)) {
            assertTrue(fire.ready(tick))
            fire.shot(tick, 2)
        }
        fire.record(Vec3.ZERO, Vec3(-1.0, 0.0, 0.0), null, 140)
        assertFalse(fire.pending(140))
        fire.record(Vec3.ZERO, Vec3(-1.0, 0.0, 0.0), null, 300)
        assertTrue(fire.pending(300))
    }
    @Test fun `shots require a peek and stale directions expire`() {
        val fire = IncomingFire()
        fire.record(Vec3.ZERO, null, Vec3(123.0, 17.0, 6.0), 0)
        assertFalse(fire.ready(10))
        assertNotEquals(Vec3(123.0, 17.0, 6.0), fire.point(10))
        fire.beginReply(20)
        assertTrue(fire.ready(20))
        assertFalse(fire.ready(80))
        assertFalse(fire.pending(240))
    }
}

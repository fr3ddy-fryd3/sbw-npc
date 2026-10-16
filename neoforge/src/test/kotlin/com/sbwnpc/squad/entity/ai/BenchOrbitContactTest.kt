package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BenchOrbitContactTest {
    @Test
    fun `sensor gaps retain a confirmed centre then return gradually to the order`() {
        val memory = BenchOrbitContact()
        val order = Vec3(100.0, 0.0, 0.0)
        val contact = Vec3.ZERO
        assertEquals(contact, memory.resolve(0, 1, order, contact))
        for (tick in 1L..BenchOrbitContact.HOLD_TICKS) {
            assertEquals(contact, memory.resolve(tick, 1, order, null))
        }
        val firstReturn = memory.resolve(BenchOrbitContact.HOLD_TICKS + 1, 1, order, null)
        assertTrue(firstReturn.x > 0.0 && firstReturn.x <= 0.4)
        for (tick in 202L..500L) memory.resolve(tick, 1, order, null)
        assertEquals(order, memory.center)
    }

    @Test
    fun `contact changes are bounded but a new order discards the old contact`() {
        val memory = BenchOrbitContact()
        memory.resolve(0, 1, Vec3.ZERO, Vec3.ZERO)
        val shifted = memory.resolve(1, 1, Vec3.ZERO, Vec3(50.0, 20.0, 50.0))
        assertTrue(shifted.horizontalDistance() <= 0.400001)
        assertTrue(shifted.y <= 0.4)
        val newOrder = Vec3(-100.0, 0.0, 40.0)
        assertEquals(newOrder, memory.resolve(2, 2, newOrder, null))
        assertNull(memory.observedAt)
        assertEquals(newOrder, memory.resolve(3, 2, newOrder, null))
    }
}
